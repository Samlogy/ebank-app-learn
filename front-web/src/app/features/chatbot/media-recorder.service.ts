import { computed, Injectable, OnDestroy, signal } from '@angular/core';

export type RecordingMode = 'voice' | 'video';

const MAX_RECORDING_SECONDS = 120;

const PREFERRED_TYPES: Record<RecordingMode, string[]> = {
  voice: ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg'],
  video: ['video/webm;codecs=vp9,opus', 'video/webm', 'video/mp4'],
};

/** Records one voice or video message at a time. Provided per chat page. */
@Injectable()
export class MediaRecorderService implements OnDestroy {
  private recorder?: MediaRecorder;
  private ticker?: ReturnType<typeof setInterval>;
  private onDone?: (file: File) => void;

  readonly mode = signal<RecordingMode | null>(null);
  readonly stream = signal<MediaStream | null>(null);
  readonly seconds = signal(0);
  readonly remaining = computed(() => MAX_RECORDING_SECONDS - this.seconds());

  get supported(): boolean {
    return typeof MediaRecorder !== 'undefined' && !!navigator.mediaDevices?.getUserMedia;
  }

  /** Resolves when recording has begun. Rejects with a message fit to show the user. */
  async start(mode: RecordingMode, onDone: (file: File) => void): Promise<void> {
    if (!this.supported) {
      throw new Error('This browser cannot record messages.');
    }
    let stream: MediaStream;
    try {
      stream = await navigator.mediaDevices.getUserMedia(
        mode === 'voice' ? { audio: true } : { audio: true, video: { facingMode: 'user' } },
      );
    } catch (error) {
      throw new Error(permissionMessage(error, mode));
    }

    const mimeType = PREFERRED_TYPES[mode].find((type) => MediaRecorder.isTypeSupported(type));
    const recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
    const chunks: Blob[] = [];
    recorder.ondataavailable = (event) => chunks.push(event.data);
    recorder.onstop = () => {
      const done = this.onDone;
      this.release();
      if (done) {
        const type = recorder.mimeType.split(';')[0];
        const name = `${mode}-message-${stamp()}.${extensionOf(type)}`;
        done(new File(chunks, name, { type }));
      }
    };

    this.recorder = recorder;
    this.onDone = onDone;
    this.stream.set(stream);
    this.mode.set(mode);
    this.seconds.set(0);
    recorder.start();
    this.ticker = setInterval(() => {
      this.seconds.update((value) => value + 1);
      if (this.remaining() <= 0) {
        this.stop();
      }
    }, 1000);
  }

  stop(): void {
    if (this.recorder?.state === 'recording') {
      this.recorder.stop();
    }
  }

  cancel(): void {
    this.onDone = undefined;
    this.stop();
  }

  ngOnDestroy(): void {
    this.cancel();
  }

  private release(): void {
    clearInterval(this.ticker);
    // Without this the browser keeps showing the camera or microphone as in use.
    this.stream()
      ?.getTracks()
      .forEach((track) => track.stop());
    this.stream.set(null);
    this.mode.set(null);
    this.recorder = undefined;
    this.onDone = undefined;
  }
}

function permissionMessage(error: unknown, mode: RecordingMode): string {
  const device = mode === 'voice' ? 'microphone' : 'camera and microphone';
  const name = error instanceof DOMException ? error.name : '';
  if (name === 'NotAllowedError') {
    return `Allow access to your ${device} in the browser settings to record.`;
  }
  return name === 'NotFoundError'
    ? `No ${device} was found on this device.`
    : `Your ${device} could not be started.`;
}

function extensionOf(mimeType: string): string {
  const subtype = mimeType.split('/')[1] ?? 'webm';
  return subtype === 'mp4' && mimeType.startsWith('audio/') ? 'm4a' : subtype;
}

function stamp(): string {
  return new Date().toISOString().replace(/[:.]/g, '-');
}
