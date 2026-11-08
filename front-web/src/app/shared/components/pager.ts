import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

@Component({
  selector: 'app-pager',
  template: `
    @if (totalPages() > 1) {
      <nav aria-label="Pages">
        <button
          class="btn small-btn"
          type="button"
          [disabled]="page() === 0"
          (click)="pageChange.emit(page() - 1)"
        >
          Previous
        </button>
        <span class="muted small tabular">Page {{ page() + 1 }} of {{ totalPages() }}</span>
        <button
          class="btn small-btn"
          type="button"
          [disabled]="page() + 1 >= totalPages()"
          (click)="pageChange.emit(page() + 1)"
        >
          Next
        </button>
      </nav>
    }
  `,
  styles: `
    nav {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 0.75rem;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Pager {
  readonly page = input.required<number>();
  readonly totalPages = input.required<number>();
  readonly pageChange = output<number>();
}
