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
package org.openthinclient.mountd;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;

import org.acplt.oncrpc.OncRpcException;
import org.apache.log4j.Logger;
import org.openthinclient.mountd.tea.MountDaemonStub;
import org.openthinclient.mountd.tea.dirpath;
import org.openthinclient.mountd.tea.exportnode;
import org.openthinclient.mountd.tea.exports;
import org.openthinclient.mountd.tea.fhandle;
import org.openthinclient.mountd.tea.fhstatus;
import org.openthinclient.mountd.tea.groupnode;
import org.openthinclient.mountd.tea.groups;
import org.openthinclient.mountd.tea.mountlist;
import org.openthinclient.mountd.tea.name;
import org.openthinclient.nfsd.PathManager;
import org.openthinclient.nfsd.StaleHandleException;
import org.openthinclient.nfsd.tea.nfs_fh;
import org.openthinclient.nfsd.tea.nfsstat;

/*
 * This code is based on 
 * JNFSD - Free NFSD. Mark Mitchell 2001 markmitche11@aol.com
 * http://hometown.aol.com/markmitche11
 */

// The mount server RPC server routines.
// Only provides mount and unmount.
// #######################################################
public class MountDaemon extends MountDaemonStub {
  private static final Logger logger = Logger.getLogger(MountDaemon.class);
  private final PathManager pathManager;
  private Exporter exporter;

  public MountDaemon(PathManager pathManager, Exporter exports, int mountdPort, int mountdProgramNumber)
      throws OncRpcException, IOException {
    super(mountdPort, mountdProgramNumber);
    this.pathManager = pathManager;
    this.exporter = exports;
  }

  // ***************************************************
  @Override
protected void MOUNTPROC_NULL_1() {
    logger.debug("NULL");
  }

  // ***************************************************
  @Override
protected fhstatus MOUNTPROC_MNT_1(InetAddress peer, dirpath params) {
    logger.debug("MNT: " + params.value);

    NFSExport export = exporter.getExport(peer, params.value.trim());

    // FIXME: check export permission
    
    fhstatus ret = new fhstatus();
    if (null == export) {
      logger.warn("MOUNT: export not found for " + params.value);
      ret.fhs_status = nfsstat.NFSERR_NOENT;
      return ret;
    }

    // convert nfs_fh to mount fhandle
    nfs_fh fh;
    try {
      fh = pathManager.getHandleForExport(export);
      ret.fhs_fhandle = new fhandle(fh.data);
    } catch (StaleHandleException e) {
      logger.error("Got StaleHandleException during mount. Should not happen.");
      ret.fhs_status = nfsstat.NFSERR_IO;
    }

    return ret;
  }

  // ***************************************************
  @Override
protected mountlist MOUNTPROC_DUMP_1() {
    logger.debug("DUMP");
    return null;
  }

  // ***************************************************
  @Override
protected void MOUNTPROC_UMNT_1(dirpath params) {
    logger.debug("UMNT");
  }

  // ***************************************************
  @Override
protected void MOUNTPROC_UMNTALL_1() {
    logger.debug("UMNTALL");
  }

  // ***************************************************
  @Override
protected exports MOUNTPROC_EXPORT_1() {
    logger.debug("EXPORT");

    exports ret = new exports();
    exports tail = ret;
    List<NFSExport> exports = exporter.getExports();
    for (NFSExport exp : exports) {
      exports export = new exports(new exportnode());

      export.value.ex_dir = new dirpath(exp.getName());
      List<NFSExport.Group> groups = exp.getGroups();
      if (null != groups) {
        // export specified list of groups
        export.value.ex_groups = new groups();
        for (NFSExport.Group group : groups) {
          groups g = new groups(new groupnode());
          g.value.gr_next = export.value.ex_groups;
          export.value.ex_groups = g;

          g.value.gr_name = new name(group.getAddress() + "/"
              + Integer.toString(group.getMask()));
        }
      } else {
        // export default group
        export.value.ex_groups = new groups(new groupnode());
        export.value.ex_groups.value.gr_name = new name("*");
        export.value.ex_groups.value.gr_next = new groups();
      }

      tail.value = export.value;
      tail.value.ex_next = export;
      export.value = null;
      tail = export;
    }

    return ret;
  }

  // ***************************************************
  @Override
protected exports MOUNTPROC_EXPORTALL_1() {
    return MOUNTPROC_EXPORT_1();
  }

  public Exporter getExporter() {
    return exporter;
  }

  public void setExporter(Exporter exports) {
    this.exporter = exports;
  }
}