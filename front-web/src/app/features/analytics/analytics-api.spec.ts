import { bars, intervalFor, lastDays } from './analytics-api';

describe('lastDays', () => {
  it('counts today as the last day, in UTC', () => {
    expect(lastDays(30, new Date('2026-11-14T23:30:00Z'))).toEqual({
      from: '2026-10-16',
      to: '2026-11-14',
    });
  });

  it('is a single day for 1', () => {
    expect(lastDays(1, new Date('2026-03-01T00:10:00Z'))).toEqual({
      from: '2026-03-01',
      to: '2026-03-01',
    });
  });
});

describe('intervalFor', () => {
  it.each([
    [7, 'DAY'],
    [31, 'DAY'],
    [90, 'WEEK'],
    [365, 'MONTH'],
  ] as const)('%i days are shown per %s', (days, interval) => {
    expect(intervalFor(days)).toBe(interval);
  });
});

describe('bars', () => {
  const points = [
    { start: '2026-11-01', incoming: 100, outgoing: 50, net: 50 },
    { start: '2026-11-02', incoming: 0, outgoing: 0, net: 0 },
  ];

  it('scales money in and out on the same axis, from the middle line', () => {
    const [first, second] = bars(points, 200, 100);
    expect(first.inHeight).toBe(50);
    expect(first.inY).toBe(0);
    expect(first.outHeight).toBe(25);
    expect(first.outY).toBe(50);
    expect(second.inHeight).toBe(0);
  });

  it('spreads the bars over the width', () => {
    const [first, second] = bars(points, 200, 100, 2);
    expect(first.x).toBe(1);
    expect(second.x).toBe(101);
    expect(first.width).toBe(98);
  });

  it('draws nothing without points', () => {
    expect(bars([], 200, 100)).toEqual([]);
  });
});
