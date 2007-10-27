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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.acplt.oncrpc.OncRpcException;
import org.apache.log4j.Logger;
import org.openthinclient.nfsd.tea.NFSServerStub;
import org.openthinclient.nfsd.tea.attrstat;
import org.openthinclient.nfsd.tea.createargs;
import org.openthinclient.nfsd.tea.dirlist;
import org.openthinclient.nfsd.tea.diropargs;
import org.openthinclient.nfsd.tea.diropokres;
import org.openthinclient.nfsd.tea.diropres;
import org.openthinclient.nfsd.tea.entry;
import org.openthinclient.nfsd.tea.filename;
import org.openthinclient.nfsd.tea.linkargs;
import org.openthinclient.nfsd.tea.nfs_fh;
import org.openthinclient.nfsd.tea.nfs_prot;
import org.openthinclient.nfsd.tea.nfscookie;
import org.openthinclient.nfsd.tea.nfsstat;
import org.openthinclient.nfsd.tea.readargs;
import org.openthinclient.nfsd.tea.readdirargs;
import org.openthinclient.nfsd.tea.readdirres;
import org.openthinclient.nfsd.tea.readlinkres;
import org.openthinclient.nfsd.tea.readokres;
import org.openthinclient.nfsd.tea.readres;
import org.openthinclient.nfsd.tea.renameargs;
import org.openthinclient.nfsd.tea.sattrargs;
import org.openthinclient.nfsd.tea.statfsokres;
import org.openthinclient.nfsd.tea.statfsres;
import org.openthinclient.nfsd.tea.symlinkargs;
import org.openthinclient.nfsd.tea.writeargs;

/*
 * JNFSD - Free NFSD. Mark Mitchell 2001 markmitche11@aol.com
 * http://hometown.aol.com/markmitche11
 */

// #######################################################
public class NFSServer extends NFSServerStub {
  static final String SOFTLINK_TAG = ".#%softlink%#";
  static final String COLON_TAG = "#%colon%#";
  private static final Logger logger = Logger.getLogger(NFSServer.class);

  /**
   * Copy a 4 byte array into an int
   * 
   * @param a
   * @param offset
   * @return
   */
  static int byteToInt(byte[] a, int offset) {
    return ((a[offset] & 0xff) << 24 | (a[offset + 1] & 0xff) << 16
        | (a[offset + 2] & 0xff) << 8 | (a[offset + 3] & 0xff));
  }

  /**
   * Copy an int into a 4 byte array
   * 
   * @param i
   * @param a
   */
  static void intToByte(int i, byte[] a) {
    a[0] = (byte) ((i >>> 24) & 0xff);
    a[1] = (byte) ((i >>> 16) & 0xff);
    a[2] = (byte) ((i >>> 8) & 0xff);
    a[3] = (byte) (i & 0xff);
  }

  private PathManager pathManager;

  /**
   * @param pathManager
   * @param port the port number to use. Specify '0', to use the default port
   * @param programNumber the rpc program number to use. Specify '0', to use the
   *          default program number
   * @throws OncRpcException
   * @throws IOException
   */
  public NFSServer(PathManager pathManager, int port, int programNumber)
      throws OncRpcException, IOException {
    super(port, programNumber);
    this.pathManager = pathManager;
  }

  @Override
protected diropres NFSPROC_CREATE_2(createargs params) {
    diropres ret = new diropres();

    try {
      NFSFile dir = pathManager.getNFSFileByHandle(params.where.dir);
      if (!dir.getFile().isDirectory()) {
        ret.status = nfsstat.NFSERR_NOENT;
        return ret;
      }
      
      String name = params.where.name.value;
      name = replaceColon(name, false);     
      File fileToCreate = makeFile(name, dir.getFile());
      if (fileToCreate == null) {
        ret.status = nfsstat.NFSERR_IO;
        return ret;
      }

      if (logger.isDebugEnabled())
        logger.debug("CREATE: " + fileToCreate);

      if (fileToCreate.exists()) {
        /*
         * This is corrent, but IRIX falls over with it.. possibly idempotence
         * issue... ret.status = nfs_misc.NFSERR_EXIST; return ret;
         */
      } else {
        // Create the file.
        try {
          if (!fileToCreate.createNewFile()) {
            if (logger.isInfoEnabled())
              logger.info("CREATE: create failed for " + fileToCreate);
            ret.status = nfsstat.NFSERR_IO;
            return ret;
          }
        } catch (SecurityException e) {
          logger.warn("CREATE: got exception for " + fileToCreate, e);
          ret.status = nfsstat.NFSERR_ACCES;
          return ret;
        } catch (IOException e) {
          if (logger.isInfoEnabled())
            logger.info("CREATE: got exception for " + fileToCreate, e);
          ret.status = nfsstat.NFSERR_IO;
          return ret;
        }

        // Now set attributes.
        try {
          // FIXME
        } catch (SecurityException e) {
          logger.warn("CREATE: got exception for " + fileToCreate, e);
          ret.status = nfsstat.NFSERR_ACCES;
          return ret;
        }
      }

      if (ret.status == 0) {
        ret.diropres = new diropokres();
        ret.diropres.file = nfs_fh.NULL_FILE_HANDLE;

        // create new handle for the file
        ret.diropres.file = pathManager.getHandleByFile(fileToCreate);
        NFSFile file = pathManager.getNFSFileByHandle(ret.diropres.file);

        ret.diropres.attributes = file.getAttributes();
      }
    } catch (StaleHandleException e) {
      logger.warn("CREATE: Got stale handle");
      ret.status = nfsstat.NFSERR_STALE;
    } catch (FileNotFoundException e) {
      ret.status = nfsstat.NFSERR_NOENT;
    }

    return ret;
  }

  /**
   * Validate the given name and make a file within the given parent directory.
   * 
   * @param params
   * @param dir
   * @return
   */
  private File makeFile(String name, File dir) {
    // prevent relative path name hacks
    if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
      logger.warn("Got fishy filename: " + name);
      return null;
    }

    return new File(dir, name.trim());
  }

  @Override
protected attrstat NFSPROC_GETATTR_2(nfs_fh params) {
    attrstat ret = new attrstat();

    NFSFile path;
    try {
      path = pathManager.getNFSFileByHandle(params);

      if (logger.isDebugEnabled())
        logger.debug("GETATTR: " + path);

      ret.attributes = path.getAttributes();
    } catch (StaleHandleException e) {
      logger.warn("GETATTR: Got stale handle");
      ret.status = nfsstat.NFSERR_STALE;
    } catch (FileNotFoundException e) {
      ret.status = nfsstat.NFSERR_NOENT;
    }

    return ret;
  }

  @Override
protected int NFSPROC_LINK_2(linkargs params) {
  	File from = new File("STALE_FILEHANLDE");
  	try {
			from = pathManager.getNFSFileByHandle(params.from).getFile();
		} catch (StaleHandleException e) {
			logger.warn("LINK: Got stale handle");
			return nfsstat.NFSERR_STALE;
		}
    logger.warn("LINK: not supported: From: " + from + " To: " + params.to.name.value);
    return nfsstat.NFSERR_ACCES;
  }

  @Override
protected diropres NFSPROC_LOOKUP_2(diropargs params) {
    diropres ret = new diropres();

    try {
      NFSFile dir = pathManager.getNFSFileByHandle(params.dir);

      String name = params.name.value;
      name = replaceColon(name, false);
      File f = makeFile(name, dir.getFile());
      if (f == null) {
        ret.status = nfsstat.NFSERR_IO;
        return ret;
      }

      if (logger.isDebugEnabled())
        logger.debug("LOOKUP: " + f);

      f = checkForLink(name, dir.getFile(), f);
      if (!f.exists())
        ret.status = nfsstat.NFSERR_NOENT;
      else {
        ret.diropres = new diropokres();
        ret.diropres.file = pathManager.getHandleByFile(f);

        NFSFile file = pathManager.getNFSFileByHandle(ret.diropres.file);

        ret.diropres.attributes = file.getAttributes();
      }
    } catch (StaleHandleException e) {
      logger.warn("LOOKUP: Got stale handle");
      ret.status = nfsstat.NFSERR_STALE;
    } catch (FileNotFoundException e) {
      ret.status = nfsstat.NFSERR_NOENT;
    }

    return ret;
  }

  /**
   * @param name
   * @param dir
   * @param f
   * @return
   */
  private File checkForLink(String name, File dir, File f) {
    if (!f.exists()) {
      File link = new File(dir, name + SOFTLINK_TAG);
      if (link.exists())
        f = link;
    }
    return f;
  }
  
  private String replaceColon(String name, boolean revert) {
  	if (!revert) {
  		if (name.contains(":")) {
  			name = name.replace(":", COLON_TAG);
  		}
  	} else
      if (name.contains(COLON_TAG)) {
      	name = name.replace(COLON_TAG, ":");
      }
  	return name;
  }

  @Override
protected diropres NFSPROC_MKDIR_2(createargs params) {
    diropres ret = new diropres();

    try {
      NFSFile dir = pathManager.getNFSFileByHandle(params.where.dir);
      
      String name = params.where.name.value;
      name = replaceColon(name, false);
      File dirToCreate = makeFile(name, dir.getFile());
      if (dirToCreate == null) {
        ret.status = nfsstat.NFSERR_IO;
        return ret;
      }
      if (logger.isDebugEnabled())
        logger.debug("MKDIR: " + dirToCreate);

      if (dirToCreate.exists()) {
        if (logger.isInfoEnabled())
          logger.info("MKDIR: directory exists " + dirToCreate);
        ret.status = nfsstat.NFSERR_EXIST;
        return ret;
      }
      try {
        if (!dirToCreate.mkdir()) {
          if (logger.isInfoEnabled())
            logger.info("MKDIR: mkdir failed for " + dirToCreate);
          ret.status = nfsstat.NFSERR_IO;
          return ret;
        }
      } catch (SecurityException e) {
        logger.warn("MKDIR: got exception for " + dirToCreate, e);
        ret.status = nfsstat.NFSERR_ACCES;
        return ret;
      }

      // Now set attributes.
      try {
      } catch (SecurityException e) {
        logger.warn("MKDIR: got exception for " + dirToCreate, e);
        ret.status = nfsstat.NFSERR_ACCES;
        return ret;
      }

      ret.diropres = new diropokres();
      ret.diropres.file = pathManager.getHandleByFile(dirToCreate);

      if (ret.status == 0) {
        NFSFile newdir = pathManager.getNFSFileByHandle(ret.diropres.file);
        ret.diropres.attributes = newdir.getAttributes();
      }
    } catch (StaleHandleException e1) {
      logger.warn("MKDIR: Got stale handle");
      ret.status = nfsstat.NFSERR_STALE;
    } catch (FileNotFoundException e) {
      ret.status = nfsstat.NFSERR_NOENT;
    }

    return ret;
  }

  @Override
protected void NFSPROC_NULL_2() {
    logger.debug("NULL");
  }

  @Override
protected readres NFSPROC_READ_2(readargs params) {
    readres ret = new readres();

    try {
      NFSFile f = pathManager.getNFSFileByHandle(params.file);

      int offset = params.offset;
      int count = Math.min(nfs_prot.NFS_MAXDATA, params.count);

      ret.reply = new readokres();
      ret.reply.data = new byte[count];

      if (logger.isDebugEnabled())
        logger.debug("READ: " + f + "," + count + " bytes " + offset
            + " offset");

      ret.reply.attributes = f.getAttributes();

      // See if it's a directory
      if (f.getFile().isDirectory()) {
        ret.status = nfsstat.NFSERR_ISDIR;
        if (logger.isInfoEnabled())
          logger.info("READ: attempt to read from drectory");
        return ret;
      }

      // Now read into ret.readokres.data
      try {
        FileChannel c = f.getChannel(false);

        ByteBuffer b = ByteBuffer.wrap(ret.reply.data);

        int total = 0;
        int read = 0;
        do {
          read = c.read(b, offset + total);
          total += read;
        } while (read > 0 && total < count);
      } catch (SecurityException e) {
        logger.warn("READ: got exception for " + f, e);
        ret.status = nfsstat.NFSERR_ACCES;
      } catch (IOException e) {
        logger.warn("READ: got exception for " + f, e);
        ret.status = nfsstat.NFSERR_IO;
      }
    } catch (StaleHandleException e1) {
      logger.warn("READ: Got stale handle");
      ret.status = nfsstat.NFSERR_STALE;
    } catch (FileNotFoundException e) {
      if (logger.isInfoEnabled())
        logger.info("READ: the file has vanished.", e);
      ret.status = nfsstat.NFSERR_NOENT;
    }

    return ret;
  }

  @Override
protected readdirres NFSPROC_READDIR_2(readdirargs params) {
    readdirres ret = new readdirres();

    try {
      NFSFile dir = pathManager.getNFSFileByHandle(params.dir);

      if (logger.isDebugEnabled())
        logger.debug("READDIR: " + dir);

      int cookie = NFSServer.byteToInt(params.cookie.value, 0);

      int size = 4 /* STATUS */
      				 + 4 /* VALUEFOLLOWS */;

      entry curr = null;

      ret.reply = new dirlist();
      ret.reply.entries = null;
      ret.reply.eof = false;
      try {
        // Get list of files
        File dirlist[] = dir.getFile().listFiles();

        // Sort files by id
				SortedMap<Integer, File> dirMapFileById = new TreeMap<Integer, File>();
				for (int i = 0; i < dirlist.length; i++) {
					dirMapFileById.put(pathManager.getIDByFile(dirlist[i]), dirlist[i]);
				}

				// Prepare idMapNextByCurrent
				int[] tmpArray = new int[dirlist.length];
				int n = 0;
				for (Iterator<Integer> i = dirMapFileById.keySet().iterator(); i.hasNext();) {
					tmpArray[n] = i.next();
					n++;
				}

				// Create fileId -> nextFileId Map to use nextFileId as cookie.
				// The cookie must be a "persistent" pointer to the next entry in the
				// directory
				Map<Integer, Integer> idMapNextByCurrent = new HashMap<Integer, Integer>();
				for (int i = 0; i < tmpArray.length; i++) {
					if (i + 1 < tmpArray.length)
						idMapNextByCurrent.put(tmpArray[i], tmpArray[i + 1]);
					else
						idMapNextByCurrent.put(tmpArray[i], 0);
				}
        
        // Walk through dirMap and fill entries to return
        for (Iterator i = dirMapFileById.entrySet().iterator(); i.hasNext(); ) {
          entry next = new entry();
        	Map.Entry pairs = (Map.Entry) i.next();
        	
          File f = (File) pairs.getValue();
          String fileName = f.getName();
          Integer fileId = (Integer) pairs.getKey();
          
          // The client only want's file(s) from a certain cookie (fileId) upwards
          if (fileId < cookie)
          	continue;
          
          next.fileid = pathManager.getIDByFile(f);
          
          if (fileName.endsWith(SOFTLINK_TAG))
            fileName = fileName.substring(0, fileName.length() - SOFTLINK_TAG.length());

          fileName = replaceColon(fileName, true);
          
          next.name = new filename(fileName);
          next.cookie = new nfscookie(new byte[nfs_prot.NFS_COOKIESIZE]);
          // Set "pointer" to next directory entry
          NFSServer.intToByte(idMapNextByCurrent.get(fileId), next.cookie.value);
          
          // Calculate size
          size += 4 /* FILEID */
                + 4 /* NAMELENGTH */
                + next.name.value.length() /* NAME */
          			+ (4 - next.name.value.length() % 4) % 4 /* FILLBYTES */
          			+ nfs_prot.NFS_COOKIESIZE
								+ 4 /* VALUEFOLLOWS */;
          
          // Bail out on requested "count" bytes
          // XXX: Better use NFSFile's attributes.blocksize instead?
          if (size + 4 /* EOF */ > params.count) {
            ret.reply.eof = false;
            return ret;
          }

          if (ret.reply.entries == null)
            ret.reply.entries = next;
          else
            curr.nextentry = next;

          curr = next;
        }
        ret.reply.eof = true;
      } catch (SecurityException e) {
        logger.warn("READDIR: got exception for " + dir, e);
        ret.status = nfsstat.NFSERR_ACCES;
      }
    } catch (StaleHandleException e1) {
      logger.warn("READDIR: Got stale handle");
      ret.status = nfsstat.NFSERR_STALE;
    }
    return ret;
  };

  @Override
protected readlinkres NFSPROC_READLINK_2(nfs_fh params) {
    readlinkres ret = new readlinkres();

    try {
      NFSFile f = pathManager.getNFSFileByHandle(params);

      try {
        ret.data = f.getLinkDestination();
      } catch (SecurityException e) {
        logger.warn("READ: got exception for " + f, e);
        ret.status = nfsstat.NFSERR_ACCES;
      } catch (IOException e) {
        if (logger.isInfoEnabled())
          logger.info("READ: got exception for " + f, e);
        ret.status = nfsstat.NFSERR_IO;
      }
    } catch (StaleHandleException e1) {
      logger.warn("READLINK: Got stale handle");
      ret.status = nfsstat.NFSERR_STALE;
    }

    return ret;
  }

  @Override
protected int NFSPROC_REMOVE_2(diropargs params) {

    try {
      NFSFile dir = pathManager.getNFSFileByHandle(params.dir);
      
      String name = params.name.value.trim();
      name = replaceColon(name, false);
      File f = makeFile(name, dir.getFile());
      
      if (f == null)
        return nfsstat.NFSERR_IO;

      if (logger.isDebugEnabled())
        logger.debug("REMOVE: " + f);

      f = checkForLink(name, dir.getFile(), f);

      if (!f.exists())
        return nfsstat.NFSERR_NOENT;

      if (pathManager.handleForFileExists(f)) {
      	nfs_fh nfsFh = pathManager.getHandleByFile(f);
      	NFSFile nfsFile = pathManager.getNFSFileByHandle(nfsFh);
      	try {
      		// Flush cache for file to avoid locking problems
          nfsFile.flushCache();
				} catch (IOException e) {
					logger.warn("REMOVE: unable to flush cache for " + nfsFile.getFile());
					return nfsstat.NFSERR_WFLUSH;
				}
      }
      
      try {
        if (!f.delete()) {
          logger.warn("REMOVE: remove failed for " + f);
          return nfsstat.NFSERR_IO;
        }
      } catch (SecurityException e) {
        logger.warn("REMOVE: got exception for " + f, e);
        return nfsstat.NFSERR_ACCES;
      } 

      pathManager.purgeFileAndHandle(f);
    } catch (StaleHandleException e1) {
      logger.warn("REMOVE: Got stale handle");
      return nfsstat.NFSERR_STALE;
		}

    return nfsstat.NFS_OK;
  }

  @Override
protected int NFSPROC_RENAME_2(renameargs params) {
    synchronized (this) {

      try {
        NFSFile fromdir = pathManager.getNFSFileByHandle(params.from.dir);
        NFSFile todir = pathManager.getNFSFileByHandle(params.to.dir);

        String fromName = params.from.name.value;
        String toName = params.to.name.value;
        fromName = replaceColon(fromName, false);
        toName =replaceColon(toName, false);
        File from = makeFile(fromName, fromdir.getFile());
        File to = makeFile(toName, todir.getFile());
        
        if (from == null || to == null)
          return nfsstat.NFSERR_IO;

        if (logger.isDebugEnabled())
          logger.debug("RENAME: " + from + " to " + to);

        from = checkForLink(from.getName(), from.getParentFile(), from);

        if (from.getName().endsWith(SOFTLINK_TAG))
        	to = makeFile(to.getName() + SOFTLINK_TAG, to.getParentFile());

        File toCheckLink = to;
        if (!to.getName().endsWith(SOFTLINK_TAG))
        	toCheckLink = checkForLink(to.getName(), to.getParentFile(), to);
        	
        // Flush cache for file to avoid locking problems
        CacheCleaner.flushAll();
        
      	File renameTemp = new File(to.getName() + ".#RENAMETEMP#");
				try {
					if (to.isFile() && to.exists()) {
						if (!to.renameTo(renameTemp)) {
							logger.warn("RENAME: rename failed for " + renameTemp);
							return nfsstat.NFSERR_IO;
						}
					}
					if (!from.renameTo(to)) {
						logger.warn("RENAME: rename failed for " + from + " to " + to);
						return nfsstat.NFSERR_IO;
					}
					pathManager.movePath(from, to);
					if (!to.equals(toCheckLink)) {
						if (!toCheckLink.delete()) {
							logger.warn("RENAME: deleting failed for link " + toCheckLink);
							return nfsstat.NFSERR_IO;
						}
						pathManager.purgeFileAndHandle(toCheckLink);
					}
				} catch (SecurityException e) {
					return nfsstat.NFSERR_ACCES;
				} catch (Exception e) {
					return nfsstat.NFSERR_IO;
				} finally {
					if (renameTemp.isFile() && renameTemp.exists()) {
						if (!renameTemp.delete()) {
							logger.warn("RENAME: deleting failed for " + renameTemp);
						}
					}
				}
			} catch (StaleHandleException e1) {
				logger.warn("RENAME: got stale handle");
				return nfsstat.NFSERR_STALE;
			}
		}

		return 0;
	}

  @Override
protected int NFSPROC_RMDIR_2(diropargs params) {
    // Does the same as remove...
    return NFSPROC_REMOVE_2(params);
  }

  @Override
protected void NFSPROC_ROOT_2() {
    logger.debug("ROOT");
  }

  @Override
protected attrstat NFSPROC_SETATTR_2(sattrargs params) {
    attrstat ret = new attrstat();

    try {
      NFSFile f = pathManager.getNFSFileByHandle(params.file);

      if (logger.isDebugEnabled())
        logger.debug("SETATTR: " + f);

      ret.attributes = f.getAttributes();

      // Now set attributes.
      // Note - sattr doesn't do anything, but for completeness...
      try {
        // FIXME
      } catch (SecurityException e) {
        logger.warn("SETATTR: got exception for " + f, e);
        ret.status = nfsstat.NFSERR_ACCES;
        return ret;
      }
    } catch (StaleHandleException e1) {
      logger.warn("SETATTR: got stale handle");
      ret.status = nfsstat.NFSERR_STALE;
    } catch (FileNotFoundException e) {
      ret.status = nfsstat.NFSERR_NOENT;
    }

    return ret;
  }

  // ***************************************************

  @Override
protected statfsres NFSPROC_STATFS_2(nfs_fh params) {
    statfsres ret = new statfsres();
    logger.debug("STATFS");
    ret.reply = new statfsokres();
    ret.reply.tsize = 8192;
    ret.reply.bsize = 8192;
    ret.reply.blocks = 100100;
    ret.reply.bfree = 100000;
    ret.reply.bavail = 100000;

    return ret;
  }

  @Override
protected int NFSPROC_SYMLINK_2(symlinkargs params) {

    try {
      NFSFile fromdir = pathManager.getNFSFileByHandle(params.from.dir);

      String dest = params.to.value;
      dest = replaceColon(dest, false);

      String fromName = params.from.name.value.trim();
      fromName = replaceColon(fromName, false);   
      File from = makeFile(fromName + SOFTLINK_TAG,
          fromdir.getFile());
      if (null == from)
        return nfsstat.NFSERR_IO;

      if (logger.isDebugEnabled())
        logger.debug("SYMLINK: " + from + " to " + dest);

      if (from.exists()) {
        logger.info("SYMLINK: file exists " + from);
        return nfsstat.NFSERR_EXIST;
      }

      try {
        FileWriter w = new FileWriter(from);
        w.write(dest);
        w.close();
      } catch (SecurityException e) {
        return nfsstat.NFSERR_ACCES;
      } catch (IOException e) {
        return nfsstat.NFSERR_IO;
      }
    } catch (StaleHandleException e1) {
      logger.warn("SYMLINK: got stale handle");
      return nfsstat.NFSERR_STALE;
    }

    return nfsstat.NFS_OK;
  }

  @Override
protected attrstat NFSPROC_WRITE_2(writeargs params) {
    attrstat ret = new attrstat();
    ret.status = 0;

    int count = params.data.length;
    int offset = params.offset;

    try {
      NFSFile f = pathManager.getNFSFileByHandle(params.file);

      if (logger.isDebugEnabled())
        logger.debug("WRITE: " + f + " of " + count + " bytes");

      // See if it's a directory
      if (f.getFile().isDirectory()) {
        ret.status = nfsstat.NFSERR_ISDIR;
        return ret;
      }

      ret.attributes = f.getAttributes();

      // we need to flush the attributes, since the subsequent
      // write will most likely change the mtime and the size.
      f.flushCachedAttributes();

      try {
        FileChannel c = f.getChannel(true);
        c.write(ByteBuffer.wrap(params.data, 0, count), offset);
      } catch (SecurityException e) {
        logger.warn("WRITE: got exception for " + f, e);
        ret.status = nfsstat.NFSERR_ACCES;
      } catch (IOException e) {
        if (logger.isInfoEnabled())
          logger.info("WRITE: got exception for " + f, e);
        ret.status = nfsstat.NFSERR_IO;
      }
    } catch (StaleHandleException e1) {
      logger.warn("WRITE: got stale handle");
      ret.status = nfsstat.NFSERR_STALE;
    } catch (FileNotFoundException e) {
      ret.status = nfsstat.NFSERR_NOENT;
    }

    return ret;
  }

  @Override
protected void NFSPROC_WRITECACHE_2() {
    logger.warn("WRITECACHE: not implemented");
  }
}