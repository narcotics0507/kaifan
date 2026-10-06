import type { ReconciliationChannel, ReconciliationRecord } from '@/service/api/report';
export const channels: Array<{ key: ReconciliationChannel; name: string }>;
export function validActual(value: unknown): boolean;
export function difference(actual: unknown, system: unknown): number | null;
export function hasDifference(actual: object, system: object): boolean;
export function reconciliationCsv(date: string, history: ReconciliationRecord[]): string;
