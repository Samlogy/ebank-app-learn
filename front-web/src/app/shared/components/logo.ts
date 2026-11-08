import { ChangeDetectionStrategy, Component } from '@angular/core';

/** Placeholder brand mark. */
@Component({
  selector: 'app-logo',
  template: `
    <svg viewBox="0 0 32 32" width="30" height="30" aria-hidden="true">
      <rect width="32" height="32" rx="9" fill="var(--accent)" />
      <path
        d="M10 9h12M10 16h9M10 23h12M10 9v14"
        fill="none"
        stroke="var(--on-accent)"
        stroke-width="3"
        stroke-linecap="round"
      />
    </svg>
    <span>EBANK</span>
  `,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      gap: 0.6rem;
      font-weight: 700;
      font-size: 1.05rem;
      letter-spacing: 0.08em;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Logo {}
