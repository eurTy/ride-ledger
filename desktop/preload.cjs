const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('rideLedgerDesktop', {
  connectPhone: () => ipcRenderer.invoke('phone:connect'),
  updateTeam: (trip, events) => ipcRenderer.invoke('team:update', trip, events),
  createTeam: (name, names) => ipcRenderer.invoke('team:create', name, names),
  teamInvite: trip => ipcRenderer.invoke('team:invite', trip),
  readTeamInvite: code => ipcRenderer.invoke('team:read-invite', code),
  readTeamImage: () => ipcRenderer.invoke('team:read-image'),
  onPeerBatch: callback => {
    ipcRenderer.on('lan:peer-batch', async (_event, batch) => {
      let added = null;
      try { added = await callback(batch); } catch { /* A failed save must not be confirmed. */ }
      await ipcRenderer.invoke('lan:peer-saved', batch.requestId, added).catch(() => {});
    });
  },
  onRemoteChanged: callback => {
    const handler = () => callback();
    ipcRenderer.on('remote:changed', handler);
    return () => ipcRenderer.removeListener('remote:changed', handler);
  }
});
