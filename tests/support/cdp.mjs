export async function connectPage(port) {
  const targets = await (await fetch('http://127.0.0.1:' + port + '/json/list')).json();
  const target = targets.find(item => item.type === 'page' && item.url.startsWith('http://127.0.0.1:'));
  if (!target) throw new Error('Desktop application page was not found');
  const socket = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, { once: true });
    socket.addEventListener('error', reject, { once: true });
  });
  let id = 0;
  const requests = new Map();
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data), request = requests.get(message.id);
    if (!request) return;
    requests.delete(message.id); clearTimeout(request.timeout);
    if (message.error) request.reject(new Error(message.error.message)); else request.resolve(message.result);
  });
  socket.addEventListener('close', () => {
    for (const request of requests.values()) { clearTimeout(request.timeout); request.reject(new Error('Desktop debugger closed')); }
    requests.clear();
  });
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const next = ++id;
    const timeout = setTimeout(() => { requests.delete(next); reject(new Error(method + ' timed out')); }, 60000);
    requests.set(next, { resolve, reject, timeout });
    socket.send(JSON.stringify({ id: next, method, params }));
  });
  const evaluate = async expression => {
    const result = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true, userGesture: true });
    if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
    return result.result.value;
  };
  await send('Page.enable'); await send('Runtime.enable');
  return { send, evaluate, close: () => socket.close(),
    screenshot: async () => Buffer.from((await send('Page.captureScreenshot', { format: 'png' })).data, 'base64') };
}
