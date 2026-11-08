import { Pipe, PipeTransform } from '@angular/core';

/** Formats with the device's locale and time zone. */
@Pipe({ name: 'localDate' })
export class LocalDatePipe implements PipeTransform {
  transform(value: string | number | Date, style: 'long' | 'time' = 'long'): string {
    const options: Intl.DateTimeFormatOptions =
      style === 'time' ? { timeStyle: 'short' } : { dateStyle: 'medium', timeStyle: 'short' };
    return new Intl.DateTimeFormat(navigator.language, options).format(new Date(value));
  }
}
