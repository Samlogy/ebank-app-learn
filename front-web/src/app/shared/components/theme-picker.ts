import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { ThemePreference, ThemeService } from '../../core/theme/theme.service';
import { Icon, IconName } from './icon';

@Component({
  selector: 'app-theme-picker',
  imports: [Icon],
  template: `
    <div class="segmented" role="group" aria-label="Theme">
      @for (option of options; track option.value) {
        <button
          type="button"
          [attr.aria-pressed]="theme.preference() === option.value"
          (click)="theme.set(option.value)"
        >
          <app-icon [name]="option.icon" />
          <span>{{ option.label }}</span>
        </button>
      }
    </div>
  `,
  styles: `
    .segmented {
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      gap: 2px;
      padding: 3px;
      border-radius: var(--radius-sm);
      background: var(--surface-2);
    }
    button {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 0.35rem;
      min-height: 2.25rem;
      padding: 0 0.6rem;
      border: 0;
      border-radius: 6px;
      background: transparent;
      color: var(--text-muted);
      font-size: 0.8125rem;
      cursor: pointer;
    }
    button[aria-pressed='true'] {
      background: var(--surface);
      color: var(--text);
      box-shadow: var(--shadow-sm);
    }
    app-icon {
      width: 1rem;
      height: 1rem;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ThemePicker {
  protected readonly theme = inject(ThemeService);
  protected readonly options: { value: ThemePreference; label: string; icon: IconName }[] = [
    { value: 'system', label: 'Device', icon: 'monitor' },
    { value: 'light', label: 'Light', icon: 'sun' },
    { value: 'dark', label: 'Dark', icon: 'moon' },
  ];
}
