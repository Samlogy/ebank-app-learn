import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

const ICONS = {
  wallet: [
    'M19 7V5a2 2 0 0 0-2-2H5a2 2 0 0 0 0 4h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5',
    'M17 14h.01',
  ],
  transfer: ['M17 3l4 4-4 4', 'M21 7H7', 'M7 21l-4-4 4-4', 'M3 17h14'],
  chart: ['M4 20V10', 'M12 20V4', 'M20 20v-7'],
  chat: ['M21 12a8.5 8.5 0 0 1-12.6 7.4L3 21l1.6-5.4A8.5 8.5 0 1 1 21 12z'],
  bell: ['M6 9a6 6 0 0 1 12 0c0 6 2.5 8 2.5 8h-17S6 15 6 9z', 'M10.3 21a2 2 0 0 0 3.4 0'],
  user: ['M20 21a8 8 0 0 0-16 0', 'M12 13a5 5 0 1 0 0-10 5 5 0 0 0 0 10z'],
  logout: ['M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4', 'M16 17l5-5-5-5', 'M21 12H9'],
  plus: ['M12 5v14', 'M5 12h14'],
  close: ['M18 6 6 18', 'M6 6l12 12'],
  send: ['M21 3 10.5 13.5', 'M21 3l-6.5 18-4-7.5L3 9.5 21 3z'],
  paperclip: [
    'M21 11.5l-8.8 8.8a5.5 5.5 0 0 1-7.8-7.8l8.9-8.8a3.7 3.7 0 0 1 5.2 5.2l-8.9 8.8a1.8 1.8 0 0 1-2.6-2.6l8.1-8.1',
  ],
  mic: [
    'M12 15a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v7a3 3 0 0 0 3 3z',
    'M19 11a7 7 0 0 1-14 0',
    'M12 18v3',
  ],
  video: [
    'M15 10l6-3.5v11L15 14',
    'M5 6h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z',
  ],
  file: ['M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z', 'M14 3v6h6'],
  download: ['M12 3v12', 'M7 10l5 5 5-5', 'M4 21h16'],
  bot: [
    'M12 8V4',
    'M6 8h12a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z',
    'M9 13v2',
    'M15 13v2',
  ],
  sun: [
    'M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10z',
    'M12 2v2',
    'M12 20v2',
    'M4.9 4.9l1.4 1.4',
    'M17.7 17.7l1.4 1.4',
    'M2 12h2',
    'M20 12h2',
    'M4.9 19.1l1.4-1.4',
    'M17.7 6.3l1.4-1.4',
  ],
  moon: ['M20.5 14.5A8.5 8.5 0 0 1 9.5 3.5a8.5 8.5 0 1 0 11 11z'],
  monitor: [
    'M4 4h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z',
    'M8 21h8',
    'M12 16v5',
  ],
  history: ['M3 12a9 9 0 1 0 3-6.7L3 8', 'M3 3v5h5', 'M12 7v5l4 2'],
  external: [
    'M15 3h6v6',
    'M10 14 21 3',
    'M18 14v5a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h5',
  ],
  check: ['M20 6 9 17l-5-5'],
} as const;

export type IconName = keyof typeof ICONS;

@Component({
  selector: 'app-icon',
  template: `
    <svg
      viewBox="0 0 24 24"
      width="100%"
      height="100%"
      fill="none"
      stroke="currentColor"
      stroke-width="1.75"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
    >
      @for (d of paths(); track d) {
        <path [attr.d]="d" />
      }
    </svg>
  `,
  styles: `
    :host {
      display: inline-flex;
      flex: none;
      width: 1.25rem;
      height: 1.25rem;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Icon {
  readonly name = input.required<IconName>();
  protected readonly paths = computed<readonly string[]>(() => ICONS[this.name()]);
}
