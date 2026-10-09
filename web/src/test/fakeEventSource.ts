// Stands in for the browser's EventSource (jsdom has none). Tests play the server's part.

type Listener = (e: MessageEvent<string>) => void;

export class FakeEventSource {
  static all: FakeEventSource[] = [];
  onerror: ((e: Event) => void) | null = null;
  closed = false;
  private listeners = new Map<string, Listener[]>();

  constructor(readonly url: string) {
    FakeEventSource.all.push(this);
  }

  static reset() {
    FakeEventSource.all = [];
  }

  static get last(): FakeEventSource {
    const s = FakeEventSource.all.at(-1);
    if (!s) throw new Error("no EventSource opened");
    return s;
  }

  addEventListener(type: string, listener: Listener) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]);
  }

  close() {
    this.closed = true;
  }

  emit(type: string, data: unknown) {
    const event = new MessageEvent(type, { data: JSON.stringify(data) });
    for (const l of this.listeners.get(type) ?? []) l(event);
  }

  fail() {
    this.onerror?.(new Event("error"));
  }
}
