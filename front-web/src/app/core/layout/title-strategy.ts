import { inject, Injectable } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { RouterStateSnapshot, TitleStrategy } from '@angular/router';

@Injectable()
export class EbankTitleStrategy extends TitleStrategy {
  private readonly pageTitle = inject(Title);

  override updateTitle(snapshot: RouterStateSnapshot): void {
    const title = this.buildTitle(snapshot);
    this.pageTitle.setTitle(title ? `${title} · EBANK` : 'EBANK');
  }
}
