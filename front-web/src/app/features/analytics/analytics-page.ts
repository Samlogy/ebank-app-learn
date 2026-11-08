import { ChangeDetectionStrategy, Component } from '@angular/core';

import { Icon } from '../../shared/components/icon';

@Component({
  selector: 'app-analytics-page',
  imports: [Icon],
  template: `
    <div class="page">
      <div class="page-header"><h1>Analytics</h1></div>
      <section class="card empty">
        <app-icon name="chart" />
        <h2>Nothing here yet</h2>
        <p>Spending insights will appear on this page.</p>
      </section>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AnalyticsPage {}
