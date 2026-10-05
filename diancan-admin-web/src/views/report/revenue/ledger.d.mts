export function money(value: unknown): string;
export function sumMoney(rows: object[], key: string): number;
export function shanghaiToday(now?: Date): string;
export function shiftDate(date: string, offset: number): string;
export function periodKey(date: string, dimension: string): string;
export function groupTrend(days: Api.Business.Revenue[], dimension: string): Api.Business.Revenue[];
