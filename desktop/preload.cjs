const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('rideLedgerDesktop', {
  getConnection: () => ipcRenderer.invoke('phone:status'),
  connectPhone: () => ipcRenderer.invoke('phone:connect'),
  stopPhone: () => ipcRenderer.invoke('phone:stop'),
  exchangeLan: snapshot => ipcRenderer.invoke('lan:exchange', snapshot),
  connectLanAddress: address => ipcRenderer.invoke('lan:connect', address),
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
