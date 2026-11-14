import { DestroyRef, effect, inject, Injectable, signal } from '@angular/core';
import { Client, IFrame, StompSubscription } from '@stomp/stompjs';
import { Observable, Subject } from 'rxjs';

import { AuthService } from '../auth/auth.service';
import { APP_CONFIG } from '../config/app-config';

/** The queues the backend lets a client subscribe to. Anything else closes the connection. */
export type UserQueue = '/user/queue/notifications' | '/user/queue/chat';

const RECONNECT_DELAY_MS = 5_000;
/** Access tokens live 5 minutes; the server closes the socket (code 4001) once the last one expires. */
const TOKEN_REFRESH_MS = 60_000;

/**
 * The one STOMP connection per signed-in user, shared by every feature.
 *
 * - The token goes in the CONNECT frame (browsers cannot set headers on the WebSocket handshake).
 * - A fresh token is sent to /app/token every minute, so the server never sees an expired one.
 * - Subscriptions are kept across reconnects; after a reconnect, features catch up through REST,
 *   because pushes are best effort.
 */
@Injectable({ providedIn: 'root' })
export class RealtimeService {
  private readonly auth = inject(AuthService);
  private readonly config = inject(APP_CONFIG);

  private readonly queues = new Map<UserQueue, { subject: Subject<unknown>; users: number }>();
  private readonly active = new Map<UserQueue, StompSubscription>();
  private readonly reconnects = new Subject<void>();
  private client?: Client;
  private refreshTimer?: ReturnType<typeof setInterval>;

  readonly connected = signal(false);
  /** Emits after every reconnect (not the first connect), so features can reload what they missed. */
  readonly reconnected$ = this.reconnects.asObservable();

  constructor() {
    // Connect while someone is signed in, disconnect on logout.
    effect(() => {
      if (this.auth.user()) {
        this.connect();
      } else {
        void this.disconnect();
      }
    });
    inject(DestroyRef).onDestroy(() => void this.disconnect());
  }

  /**
   * Messages pushed on a user queue, parsed from JSON. The server only sees the SUBSCRIBE while at
   * least one caller is subscribed: for /user/queue/chat that is what makes an advisor "online".
   */
  watch<T>(queue: UserQueue): Observable<T> {
    return new Observable<T>((observer) => {
      let entry = this.queues.get(queue);
      if (!entry) {
        entry = { subject: new Subject<unknown>(), users: 0 };
        this.queues.set(queue, entry);
      }
      entry.users++;
      const subscription = entry.subject.subscribe((value) => observer.next(value as T));
      this.subscribeOnServer(queue);
      return () => {
        subscription.unsubscribe();
        entry.users--;
        if (entry.users === 0) {
          this.queues.delete(queue);
          this.active.get(queue)?.unsubscribe();
          this.active.delete(queue);
        }
      };
    });
  }

  private connect(): void {
    if (this.client) {
      return;
    }
    let connectedBefore = false;
    const client = new Client({
      brokerURL: this.config.wsUrl,
      reconnectDelay: RECONNECT_DELAY_MS,
      heartbeatIncoming: 0,
      heartbeatOutgoing: 0,
      // Runs before every (re)connect, so each CONNECT carries a token that is still valid.
      beforeConnect: async () => {
        client.connectHeaders = { Authorization: `Bearer ${await this.auth.accessToken()}` };
      },
      onConnect: () => {
        this.active.clear();
        for (const queue of this.queues.keys()) {
          this.subscribeOnServer(queue);
        }
        this.connected.set(true);
        if (connectedBefore) {
          this.reconnects.next();
        }
        connectedBefore = true;
      },
      onWebSocketClose: () => {
        this.active.clear();
        this.connected.set(false);
      },
      onStompError: (frame: IFrame) =>
        console.warn('Realtime connection refused:', frame.headers['message']),
    });
    this.client = client;
    client.activate();
    this.refreshTimer = setInterval(() => void this.sendFreshToken(), TOKEN_REFRESH_MS);
  }

  private async disconnect(): Promise<void> {
    clearInterval(this.refreshTimer);
    const client = this.client;
    this.client = undefined;
    this.active.clear();
    this.connected.set(false);
    await client?.deactivate();
  }

  private subscribeOnServer(queue: UserQueue): void {
    const client = this.client;
    if (!client?.connected || this.active.has(queue)) {
      return;
    }
    const subscription = client.subscribe(queue, (message) => {
      try {
        this.queues.get(queue)?.subject.next(JSON.parse(message.body));
      } catch {
        // A frame that is not JSON is not ours to show.
      }
    });
    this.active.set(queue, subscription);
  }

  private async sendFreshToken(): Promise<void> {
    if (!this.client?.connected) {
      return;
    }
    try {
      const token = await this.auth.accessToken();
      this.client.publish({
        destination: '/app/token',
        headers: { Authorization: `Bearer ${token}` },
      });
    } catch {
      // No token right now: the server will close the socket and the client reconnects later.
    }
  }
}
