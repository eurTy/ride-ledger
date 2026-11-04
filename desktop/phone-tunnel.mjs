import { spawn } from 'node:child_process';
import { EventEmitter } from 'node:events';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';

export class PhoneTunnel extends EventEmitter {
  constructor({ port, userData, instanceId }) {
    super();
    this.port = port; this.userData = userData; this.instanceId = instanceId;
    this.status = { state: 'idle', url: '', message: '分享邀请码时自动连接手机' };
    this.child = null; this.pending = null; this.generation = 0;
  }
  publish(status) { this.status = status; this.emit('status', status); }
  async start() {
    if (this.status.state === 'ready') return this.status;
    if (this.pending) return this.pending;
    const generation = ++this.generation;
    this.pending = (async () => {
      await mkdir(this.userData, { recursive: true });
      if (generation !== this.generation) throw new Error('手机连接已取消');
      this.publish({ state: 'connecting', url: '', message: '正在建立手机连接…' });
      return this.open();
    })().finally(() => { this.pending = null; });
    return this.pending;
  }
  open() {
    return new Promise((resolve, reject) => {
      const knownHosts = path.join(this.userData, 'ssh-known-hosts').replace(/\\/g, '/');
      const ssh = process.platform === 'win32'
        ? path.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'OpenSSH', 'ssh.exe') : 'ssh';
      const child = spawn(ssh, ['-T', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15',
        '-o', 'ExitOnForwardFailure=yes', '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=3',
        '-o', 'StrictHostKeyChecking=accept-new', '-o', 'UserKnownHostsFile="' + knownHosts + '"',
        '-R', '80:127.0.0.1:' + this.port, 'nokey@localhost.run'],
      { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
      this.child = child;
      let settled = false, output = '';
      const checking = new Set();
      const fail = message => {
        clearTimeout(timeout);
        if (!settled) { settled = true; reject(new Error(message)); }
        if (this.child === child) {
          this.child = null;
          this.publish({ state: 'offline', url: '', message });
        }
        child.kill();
      };
      const timeout = setTimeout(() => fail('手机连接暂时不可用，请检查网络后重新分享邀请码'), 45000);
      const read = chunk => {
        output = (output + chunk.toString()).replace(/\x1b\[[0-9;]*m/g, '').slice(-16000);
        for (const match of output.matchAll(/https:\/\/[a-z0-9-]+\.(?:lhr\.life|localhost\.run)(?=[/\s]|$)/gi)) {
          const url = match[0];
          if (checking.has(url) || settled) continue;
          checking.add(url);
          fetch(url + '/health', { signal: AbortSignal.timeout(10000) }).then(response => {
            if (!response.ok) throw new Error('unreachable');
            return response.json();
          }).then(health => {
            if (health.service !== 'ride-ledger' || health.instanceId !== this.instanceId)
              throw new Error('unexpected relay');
            if (settled || this.child !== child) return;
            settled = true; clearTimeout(timeout);
            this.publish({ state: 'ready', url, message: '手机连接已就绪 · 请保持电脑运行' });
            resolve(this.status);
          }).catch(() => { checking.delete(url); });
        }
      };
      child.stdout.on('data', read); child.stderr.on('data', read);
      child.once('error', () => fail('无法启动系统 SSH，请在 Windows 启用 OpenSSH 客户端后重试'));
      child.once('exit', () => {
        clearTimeout(timeout);
        if (!settled) { settled = true; reject(new Error('手机连接未成功，请检查网络后重试')); }
        if (this.child === child) {
          this.child = null;
          this.publish({ state: 'offline', url: '', message: '手机连接已中断，请重新分享邀请码更新连接' });
        }
      });
    });
  }
  stop() {
    this.generation++;
    const child = this.child; this.child = null;
    if (child) child.kill();
    this.publish({ state: 'idle', url: '', message: '手机连接已关闭' });
  }
}
