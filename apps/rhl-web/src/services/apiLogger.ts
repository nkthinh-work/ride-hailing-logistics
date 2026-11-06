import type { ApiLogEntry } from '../types/index.ts';


type LogListener = (entry: ApiLogEntry) => void;

class ApiLoggerService {
  private logs: ApiLogEntry[] = [];
  private listeners: Set<LogListener> = new Set();
  private maxLogs = 100;

  public log(entry: ApiLogEntry) {
    this.logs.unshift(entry);
    if (this.logs.length > this.maxLogs) {
      this.logs.pop();
    }
    this.listeners.forEach((listener) => listener(entry));
  }

  public getLogs(): ApiLogEntry[] {
    return [...this.logs];
  }

  public clearLogs() {
    this.logs = [];
    this.listeners.forEach((listener) =>
      listener({
        id: 'clear',
        timestamp: new Date().toISOString(),
        method: 'SYSTEM',
        url: 'Console logs cleared',
        status: 200,
        durationMs: 0,
        correlationId: '',
        success: true,
      })
    );
  }

  public subscribe(listener: LogListener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }
}

export const apiLogger = new ApiLoggerService();
