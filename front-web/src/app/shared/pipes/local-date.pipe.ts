import { Pipe, PipeTransform } from '@angular/core';

const OPTIONS: Record<'long' | 'time' | 'date', Intl.DateTimeFormatOptions> = {
  long: { dateStyle: 'medium', timeStyle: 'short' },
  time: { timeStyle: 'short' },
  // A calendar day such as the API's yyyy-MM-dd: shown as is, never shifted by the time zone.
  date: { dateStyle: 'medium', timeZone: 'UTC' },
};

/** Formats with the device's locale and time zone (except 'date', a UTC calendar day). */
@Pipe({ name: 'localDate' })
export class LocalDatePipe implements PipeTransform {
  transform(value: string | number | Date, style: 'long' | 'time' | 'date' = 'long'): string {
    return new Intl.DateTimeFormat(navigator.language, OPTIONS[style]).format(new Date(value));
  }
}
