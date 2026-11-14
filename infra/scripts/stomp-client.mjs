#!/usr/bin/env node
// Minimal STOMP 1.2 client over WebSocket, for testing /ws by hand. No dependencies (Node 22+).
//
//   node stomp-client.mjs --token "$TOKEN"                       listen to your notifications
//   node stomp-client.mjs --token "$TOKEN" --count 1 --timeout 20  exit after the first one
//   node stomp-client.mjs --token "$TOKEN" --subscribe /queue/x    try a forbidden subscription
//   node stomp-client.mjs --token "$TOKEN" --refresh "$NEW_TOKEN"  extend the connection
//
// Prints one line per event: CONNECTED, SUBSCRIBED, MESSAGE <json>, REFRESHED, ERROR <reason>, CLOSED <code>.
// Exit code: 0 when --count messages arrived (or the connection ended normally), 1 on ERROR, 2 on timeout.

const args = Object.fromEntries(
  process.argv.slice(2).reduce((pairs, value, i, all) => {
    if (value.startsWith('--')) pairs.push([value.slice(2), all[i + 1]?.startsWith('--') ? 'true' : all[i + 1] ?? 'true']);
    return pairs;
  }, []),
);

const url = args.url ?? process.env.WS_URL ?? 'ws://localhost:8080/ws';
const token = args.token ?? '';
const destination = args.subscribe ?? '/user/queue/notifications';
const count = Number(args.count ?? 0);            // 0 = keep listening
const timeoutSeconds = Number(args.timeout ?? 0); // 0 = no timeout
const refreshToken = args.refresh;
const origin = args.origin ?? 'http://localhost:4200';

function frame(command, headers = {}, body = '') {
  const lines = Object.entries(headers).map(([k, v]) => `${k}:${v}`);
  return `${command}\n${lines.join('\n')}\n\n${body}\0`;
}

function parse(raw) {
  const [head, ...rest] = raw.split('\n\n');
  const [command, ...headerLines] = head.split('\n');
  const headers = Object.fromEntries(headerLines.map((l) => [l.slice(0, l.indexOf(':')), l.slice(l.indexOf(':') + 1)]));
  return { command: command.trim(), headers, body: rest.join('\n\n') };
}

let received = 0;
let exitCode = 0;
const socket = new WebSocket(url, { headers: { Origin: origin } });

if (timeoutSeconds > 0) {
  setTimeout(() => {
    console.log(`TIMEOUT after ${timeoutSeconds}s (${received} message(s))`);
    process.exit(2);
  }, timeoutSeconds * 1000);
}

socket.addEventListener('open', () => {
  const headers = { 'accept-version': '1.2', host: 'localhost', 'heart-beat': '0,0' };
  if (token) headers.Authorization = `Bearer ${token}`;
  socket.send(frame('CONNECT', headers));
});

let buffer = '';
socket.addEventListener('message', async (event) => {
  buffer += typeof event.data === 'string' ? event.data : Buffer.from(await event.data.arrayBuffer()).toString();
  let end;
  while ((end = buffer.indexOf('\0')) >= 0) {
    const raw = buffer.slice(0, end).replace(/^\n+/, '');
    buffer = buffer.slice(end + 1);
    if (!raw) continue; // heartbeat
    const { command, headers, body } = parse(raw);
    switch (command) {
      case 'CONNECTED':
        console.log(`CONNECTED user=${headers['user-name'] ?? '?'}`);
        socket.send(frame('SUBSCRIBE', { id: 'sub-0', destination }));
        console.log(`SUBSCRIBED ${destination}`);
        if (refreshToken) {
          setTimeout(() => {
            socket.send(frame('SEND', { destination: '/app/token', Authorization: `Bearer ${refreshToken}` }));
            console.log('REFRESHED');
          }, 300);
        }
        break;
      case 'MESSAGE':
        received++;
        console.log(`MESSAGE ${body}`);
        if (count > 0 && received >= count) {
          socket.send(frame('DISCONNECT'));
          socket.close(1000);
        }
        break;
      case 'ERROR':
        console.log(`ERROR ${headers.message ?? body}`);
        exitCode = 1;
        break;
      default:
        console.log(`${command} ${body}`);
    }
  }
});

socket.addEventListener('close', (event) => {
  console.log(`CLOSED ${event.code}${event.reason ? ' ' + event.reason : ''}`);
  process.exit(exitCode);
});

socket.addEventListener('error', () => {
  console.log('ERROR websocket connection failed');
  exitCode = 1;
});
