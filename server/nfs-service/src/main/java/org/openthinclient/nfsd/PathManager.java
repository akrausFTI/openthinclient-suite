/*******************************************************************************
 * openthinclient.org ThinClient suite
 * 
 * Copyright (C) 2004, 2007 levigo holding GmbH. All Rights Reserved.
 * 
 * 
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 59 Temple
 * Place - Suite 330, Boston, MA 02111-1307, USA.
 *******************************************************************************/
/*
 * This code is based on: 
 * JNFSD - Free NFSD. Mark Mitchell 2001 markmitche11@aol.com
 * http://hometown.aol.com/markmitche11
 */
package org.openthinclient.nfsd;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.openthinclient.mountd.Exporter;
import org.openthinclient.mountd.NFSExport;
import org.openthinclient.nfsd.tea.nfs_fh;
import org.openthinclient.nfsd.tea.nfs_prot;

import com.levigo.util.collections.IntHashtable;

/**
 * @author Joerg Henne
 */
public class PathManager {
	static final Logger logger = Logger.getLogger(PathManager.class);

	private final File handleDatabase;

	private final IntHashtable handlesToFiles;
	private final Map<File, nfs_fh> filesToHandles;
	private boolean isChanged;

	private final byte handleGeneration[];

	private int currentHandleCounter = 0;

	private final Exporter exporter;

	/**
	 * Construct a new PathManager. The path data is persisted into the specified
	 * path database.
	 * 
	 * @throws IOException If the given handle database is not writable.
	 */
	public PathManager(File handleDatabase, Exporter exporter) throws IOException {
		this.handleDatabase = handleDatabase;
		this.exporter = exporter;
		handlesToFiles = new IntHashtable();
		filesToHandles = new HashMap<File, nfs_fh>();
		isChanged = true;

		// initialize the handle generation
		handleGeneration = new byte[8];
		long currentTimeMillis = System.currentTimeMillis();
		handleGeneration[0] = (byte) (currentTimeMillis >> 56 & 0xff | 1);
		handleGeneration[1] = (byte) (currentTimeMillis >> 48 & 0xff);
		handleGeneration[2] = (byte) (currentTimeMillis >> 40 & 0xff);
		handleGeneration[3] = (byte) (currentTimeMillis >> 32 & 0xff);
		handleGeneration[4] = (byte) (currentTimeMillis >> 24 & 0xff);
		handleGeneration[5] = (byte) (currentTimeMillis >> 16 & 0xff);
		handleGeneration[6] = (byte) (currentTimeMillis >> 8 & 0xff);
		handleGeneration[7] = (byte) (currentTimeMillis & 0xff);

		loadPathDatabase();
	}

	private void loadPathDatabase() throws IOException {
		if (null != handleDatabase) {
			if (handleDatabase.exists() && !handleDatabase.canWrite())
				throw new IOException("The handle database must be writable.");

			// make sure that we can create a tmp path database.
			File tmp = File.createTempFile("paths", ".db", handleDatabase
					.getAbsoluteFile().getParentFile());
			if (null == tmp)
				throw new IOException("Can't create tmp handle database at " + tmp);
			tmp.delete();

			// does it exist yet?
			if (handleDatabase.exists()) {
				logger.info("Loading path database at " + handleDatabase);

				// Get the exports currently served by the exporter
				List<NFSExport> exports = exporter.getExports();

				// actually load the path database.
				BufferedReader br = new BufferedReader(new FileReader(handleDatabase));

				// the path database consists of a simple text format:
				// ggggggggggggggiiiiiiii <filename>
				// with g denoting the handle generation (a long in hex format),
				// i the file id (an int in hex format)
				// and the filename.
				while (true) {
					String line = br.readLine();
					if (null == line)
						break;
					try {
						nfs_fh fh = new nfs_fh(new byte[nfs_prot.NFS_FHSIZE]);
						parseHex(line, 0, fh.data, 12);

						File path = new File(line.substring(25));
						if (!(path.exists() || path.isHidden())) {
							if (logger.isInfoEnabled())
								logger.info("Not loading nonexistent path " + path);
							continue;
						}

						// find corresponding export.
						// if several exports match the path, the one with the best
						// (longest)
						// match is chosen.
						NFSExport bestMatch = null;
						int bestLength = 0;
						for (NFSExport export : exports) {
							String exportRoot = export.getRoot().getAbsolutePath();
							if (path.getAbsolutePath().startsWith(exportRoot)
									&& exportRoot.length() > bestLength) {
								bestMatch = export;
								bestLength = exportRoot.length();
							}
						}

						// did we find an export?
						if (null == bestMatch) {
							if (logger.isInfoEnabled())
								logger.info("Path seems to be no longer exported: " + path);
							continue;
						}

						int id = handleToInt(fh);
						currentHandleCounter = Math.max(currentHandleCounter, id + 1);
						handlesToFiles.put(id, new NFSFile(fh, path, null, bestMatch));
						filesToHandles.put(path, fh);
					} catch (Exception e) {
						// ignore the line when parsing failed.
						logger.warn("Can't parse this line: " + line);
					}
				}

				// resolve parent-child relationships
				List<NFSFile> filesToRemove = new ArrayList<NFSFile>();
				for (Enumeration<NFSFile> i = handlesToFiles.elements(); i
						.hasMoreElements();) {
					NFSFile file = i.nextElement();
					File parent = file.getFile().getParentFile();

					nfs_fh parentHandle = filesToHandles.get(parent);
					// is there no parent handle? This would be ok, if the file
					// corresponded to the export root.
					if (null == parentHandle
							&& !file.getFile().equals(file.getExport().getRoot())) {
						logger.warn("Parent for file " + file.getFile()
								+ " not found in handle database.");
						filesToRemove.add(file);
					} else if (null == parentHandle) {
						// this is a fs root. just leave the <null> parent
					} else {
						NFSFile parentFile = (NFSFile) handlesToFiles
								.get(handleToInt(parentHandle));
						if (null == parentFile) {
							logger
									.warn("Parent file for handle not found. Should not happen!");
							filesToRemove.add(file);
						} else
							file.setParentDirectory(parentFile);
					}
				}

				// get rid of files that have to be dumped
				for (Iterator<NFSFile> i = filesToRemove.iterator(); i.hasNext();) {
					NFSFile file = i.next();
					filesToHandles.remove(file.getFile());
					handlesToFiles.remove(handleToInt(file.getHandle()));
				}

				isChanged = false;
			}
		}
	}

	/**
	 * With very high loads and a large number of files it might by problematic to
	 * flush the path database synchronously. In this case we would need to do
	 * something a lot smarter. However, for now this seems to be ok.
	 * 
	 * @throws IOException
	 */
	public synchronized void flushPathDatabase() throws IOException {
		if (null != handleDatabase && isChanged) {
			if (handleDatabase.exists() && !handleDatabase.canWrite())
				throw new IOException("The handle database must be writable.");

			// make sure that we can create a tmp path database.
			File tmp = File.createTempFile("paths", ".db", handleDatabase
					.getParentFile());
			if (null == tmp)
				throw new IOException("Can't create tmp handle database at " + tmp);

			logger.info("Saving path database at " + handleDatabase);

			// actually save the path database.
			BufferedWriter bw = new BufferedWriter(new FileWriter(tmp));

			for (Enumeration<NFSFile> i = handlesToFiles.elements(); i
					.hasMoreElements();) {
				NFSFile file = i.nextElement();
				toHex(file.getHandle().data, 0, 12, bw);
				bw.write(' ');
				bw.write(file.getFile().getAbsolutePath());
				bw.write('\n');
			}

			bw.close();

			File handleDBBackup = new File(handleDatabase.getAbsolutePath() + "~");
			handleDBBackup.delete();
			handleDatabase.renameTo(handleDBBackup);
			tmp.renameTo(handleDatabase);

			isChanged = false;
		}
	}

	private void toHex(byte[] bs, int offset, int length, BufferedWriter bw)
			throws IOException {
		int end = offset + length;
		for (int i = offset; i < end; i++) {
			bw.write(intToChar(bs[i] >> 4 & 0xf));
			bw.write(intToChar(bs[i] & 0xf));
		}
	}

	private char intToChar(int i) {
		if (i >= 0 && i <= 9)
			return (char) (i + '0');
		else if (i >= 0xa && i <= 0xf)
			return (char) (i + 'a' - 0xa);
		else
			throw new IllegalArgumentException();
	}

	private static void parseHex(String line, int start, byte bytes[], int length) {
		int charIdx = start;
		for (int i = 0; i < length; i += 1) {
			bytes[i] = (byte) (charToInt(line.charAt(charIdx++)) << 4);
			bytes[i] |= (byte) charToInt(line.charAt(charIdx++));
		}
	}

	private static int charToInt(char c) {
		if (c >= '0' && c <= '9')
			return c - '0';
		else if (c >= 'a' && c <= 'f')
			return c - 'a' + 0xa;
		else if (c >= 'A' && c <= 'F')
			return c - 'A' + 0xa;
		else
			throw new NumberFormatException("Illegal hex character " + c);
	}

	static int handleToInt(nfs_fh handle) {
		byte h[] = handle.data;
		return ((0xff & h[8]) << 24) | ((0xff & h[9]) << 16)
				| ((0xff & h[10]) << 8) | (0xff & h[11]);
	}

	/**
	 * Get an NFSFile by its nfs_fh handle. The file has to exist.
	 * 
	 * @param fh
	 * @return
	 * @throws StaleHandleException if the handle is not defined or the handle
	 *           generation doesn't match.
	 */
	public NFSFile getNFSFileByHandle(nfs_fh fh) throws StaleHandleException {
		NFSFile nfsFile = (NFSFile) handlesToFiles.get(handleToInt(fh));
		if (null == nfsFile)
			throw new StaleHandleException();

		if (!nfsFile.validateHandle(fh))
			throw new StaleHandleException("Handle was defined, but wrong generation");

		nfsFile.updateTimestamp();
		return nfsFile;
	}

	public synchronized boolean handleForFileExists(File f) {
		return filesToHandles.get(f) != null;
	}

	public synchronized nfs_fh getHandleByFile(File f)
			throws StaleHandleException {
		// we like em better
		if (!f.isAbsolute())
			f = f.getAbsoluteFile();

		nfs_fh handle = filesToHandles.get(f);
		if (null == handle) {
			handle = createHandleForFile(f, null);
			filesToHandles.put(f, handle);
			isChanged = true;
		}
		return handle;
	}

	public synchronized int getIDByFile(File f) throws StaleHandleException {
		nfs_fh handle = filesToHandles.get(f);
		if (null == handle) {
			handle = createHandleForFile(f, null);
			filesToHandles.put(f, handle);
			isChanged = true;
		}
		return getIDFromHandle(handle);
	}

	/**
	 * @param handle
	 * @return
	 */
	private static int getIDFromHandle(nfs_fh handle) {
		return NFSServer.byteToInt(handle.data, 8);

	}

	private nfs_fh createHandleForFile(File f, NFSExport export)
			throws StaleHandleException {
		nfs_fh fh = new nfs_fh(new byte[nfs_prot.NFS_FHSIZE]);

		System.arraycopy(handleGeneration, 0, fh.data, 0, 8);

		int h = currentHandleCounter++;
		fh.data[8] = (byte) (h >> 24 & 0xff);
		fh.data[9] = (byte) (h >> 16 & 0xff);
		fh.data[10] = (byte) (h >> 8 & 0xff);
		fh.data[11] = (byte) (h & 0xff);

		if (null == export) {
			// find NFSFile for parent directory
			File parentFile = f.getParentFile();
			nfs_fh parentHandle = filesToHandles.get(parentFile);
			if (null == parentHandle)
				throw new StaleHandleException(f + " doesn't have a parent handle");
			int id = getIDFromHandle(parentHandle);
			NFSFile parent = (NFSFile) handlesToFiles.get(id);
			if (null == parent)
				throw new StaleHandleException("Not NFS file for parent handle for "
						+ f);

			handlesToFiles.put(h, new NFSFile(fh, f, parent, parent.getExport()));
		} else {
			handlesToFiles.put(h, new NFSFile(fh, f, null, export));
		}

		isChanged = true;

		return fh;
	}

	public nfs_fh getHandleForExport(NFSExport e) throws StaleHandleException {
		File root = e.getRoot();

		// we like em better
		if (!root.isAbsolute())
			root = root.getAbsoluteFile();

		nfs_fh handle = filesToHandles.get(root);
		if (null == handle) {
			handle = createHandleForFile(root, e);
			filesToHandles.put(root, handle);
			isChanged = true;
		}

		return handle;
	}

	public synchronized void purgeFileAndHandle(File f)
			throws StaleHandleException {
		// we like em better
		if (!f.isAbsolute())
			f = f.getAbsoluteFile();

		handlesToFiles.remove(getIDByFile(f));
		filesToHandles.remove(f);
	}

	public synchronized void movePath(File from, File to) {
		LinkedHashMap<File, File> moveMap;
		moveMap = new LinkedHashMap<File, File>();

		LinkedHashMap<File, File> recursiveMoveMap = getMoveMap(from, to, moveMap);

		for (Iterator i = recursiveMoveMap.entrySet().iterator(); i.hasNext();) {
			Map.Entry pairs = (Map.Entry) i.next();
			moveMapEntries((File) pairs.getKey(), (File) pairs.getValue());
		}
		try {
			isChanged=true;
			flushPathDatabase();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private synchronized LinkedHashMap<File, File> getMoveMap(File from, File to,
			LinkedHashMap<File, File> sortedMoveMap) {
		if (!from.isAbsolute())
			from = from.getAbsoluteFile();
		if (!to.isAbsolute())
			to = to.getAbsoluteFile();

		// did the to-File exist before? Get rid of the handles!
		nfs_fh fh = filesToHandles.get(to);
		if (null != fh) {
			filesToHandles.remove(to);
			handlesToFiles.remove(handleToInt(fh));
		}
		// put entry itself
		sortedMoveMap.put(from, to);

		// if the entry is a directory, move the contained files
		if (to.isDirectory()) {
			// and recursively collect map entries to move
			for (Iterator<File> i = filesToHandles.keySet().iterator(); i.hasNext();) {
				File f = i.next();
				if (f.getParentFile().equals(from)) {
					File t = new File(to, f.getName());
					getMoveMap(f, t, sortedMoveMap);
					sortedMoveMap.put(f, t);
				}
			}
		}

		return sortedMoveMap;
	}

	/**
	 * @param from
	 * @param to
	 */
	private synchronized void moveMapEntries(File from, File to) {

		nfs_fh fhFrom = filesToHandles.get(from);
		if (null == fhFrom)
			try {
				fhFrom = getHandleByFile(from);
			} catch (StaleHandleException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
		if (null != fhFrom) {
			filesToHandles.remove(from);
			NFSFile nfsFileFrom = (NFSFile) handlesToFiles.get(handleToInt(fhFrom));

			File parentFileTo = to.getParentFile();
			nfs_fh parentHandleTo = filesToHandles.get(parentFileTo);

			if (null == parentHandleTo)
				try {
					parentHandleTo = getHandleByFile(to.getParentFile());
				} catch (StaleHandleException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}

			if (null != parentHandleTo) {
				int id = getIDFromHandle(parentHandleTo);
				NFSFile parentTo = (NFSFile) handlesToFiles.get(id);

				if (null != parentTo) {
					NFSFile nfsFileTo = new NFSFile(nfsFileFrom.getHandle(), to,
							parentTo, parentTo.getExport());
					filesToHandles.put(to, fhFrom);
					handlesToFiles.put(handleToInt(fhFrom), nfsFileTo);
				} else
					logger.warn("missing handle->file map for parentHandleTo: "
							+ parentHandleTo);
			} else
				logger.warn("missing file->handle map for parentFileTo: "
						+ parentFileTo);

			isChanged = true;
		} else
			logger.warn("missing file->handle map for from: " + from);
	}

	/**
	 * @throws Exception if something goes wrong. Yes, I don't want to be more
	 *           specific here.
	 */
	public synchronized void shutdown() throws Exception {
		flushPathDatabase();
	}

	public void buildAndFlushFile(File f) {
		try {
			nfs_fh fh = getHandleByFile(f);
			NFSFile file = getNFSFileByHandle(fh);
			file.flushCache();
		} catch (StaleHandleException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
/**
 * 
 * @param f An existing directory path
 * @return true only if the given directory path could be translated to the NFS Database
 */
	public boolean checkAndCreateDirectorys(File f) {
		// If the "new" file already exists in the nfs database there is an
		// extreamly failure...

		try {
			// if f is an file it is absolutly necessary to make an Directory out of
			// that...
			if (f.isFile())
				f.getParentFile();
			// fullDirectory means that here is the full Directory path saved
			if (null != filesToHandles.get(f)) {
				return true;
			}
			File fullDirectory = new File(f.getAbsolutePath());
			while (null == filesToHandles.get(f.getParentFile()) && null != f)
				f = f.getParentFile();

			if (f == null) {
				logger.error("f==null");
				return false;
			}
			// root means that here is the last pdirectory which is in the nfs db
			// saved
			nfs_fh fh = getHandleByFile(f.getParentFile());
			File root = new File(f.getAbsolutePath());
			NFSFile nfsfile = new NFSFile(getHandleByFile(f), f,
					getNFSFileByHandle(fh), getNFSFileByHandle(fh).getExport());
			// in this while there really is something done! here the different
			// directorys will be taken into the nfs db
			while (!(root.getAbsolutePath().equalsIgnoreCase(fullDirectory
					.getAbsolutePath()))) {
				f = new File(fullDirectory.getAbsolutePath());
				while (!(root.getAbsolutePath().equalsIgnoreCase(f.getParent())))
					f = f.getParentFile();
				nfsfile = new NFSFile(getHandleByFile(f), f, nfsfile, nfsfile
						.getExport());
				root = new File(f.getAbsolutePath());

			}
			return true;

		} catch (StaleHandleException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return false;
	}
/**
 * 
 * @param filesList list of files which should be removed from the NFS DB
 * @return true only if all files could be deleted from the NFS Database
 */
	public boolean removeFileFromNFS(Collection<File> filesList) {
		for (File file : filesList) {
			if (file.delete()) {
				if (handleForFileExists(file)) {
					try {
						if(!file.isAbsolute())
							file=file.getAbsoluteFile();
						handlesToFiles.remove(getIDByFile(file));
						
						filesToHandles.remove(file);
					} catch (StaleHandleException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					
				}
			} else if (file.isFile()) {
				logger.warn("cant move File: " + file.getPath());
				return false;
			}
		}
		try {
			isChanged=true;
			flushPathDatabase();
		} catch (IOException e) {
			return false;
		}
		return true;
	}
}