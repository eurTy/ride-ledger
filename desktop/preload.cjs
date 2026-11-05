const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('rideLedgerDesktop', {
  getConnection: () => ipcRenderer.invoke('phone:status'),
  connectPhone: () => ipcRenderer.invoke('phone:connect'),
  stopPhone: () => ipcRenderer.invoke('phone:stop'),
  exchangeLan: snapshot => ipcRenderer.invoke('lan:exchange', snapshot),
  connectLanAddress: address => ipcRenderer.invoke('lan:connect', address),
  onPeerBatch: callback => {
    ipcRenderer.on('lan:peer-batch', async (_event, batch) => {
      let added = null;
      try { added = await callback(batch); } catch { /* A failed save must not be confirmed. */ }
      await ipcRenderer.invoke('lan:peer-saved', batch.requestId, added).catch(() => {});
    });
  },
  onConnectionChanged: callback => {
    const handler = (_event, status) => callback(status);
    ipcRenderer.on('phone:status', handler);
    return () => ipcRenderer.removeListener('phone:status', handler);
  },
  onRemoteChanged: callback => {
    const handler = () => callback();
    ipcRenderer.on('remote:changed', handler);
    return () => ipcRenderer.removeListener('remote:changed', handler);
  }
});
