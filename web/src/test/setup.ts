import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterAll, afterEach, beforeAll, beforeEach, vi } from "vitest";

import { auth } from "../api/session";
import { FakeEventSource } from "./fakeEventSource";
import { server } from "./server";

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
beforeEach(() => {
  FakeEventSource.reset();
  vi.stubGlobal("EventSource", FakeEventSource);
});
afterEach(() => {
  cleanup();
  server.resetHandlers();
  auth.signOut();
  localStorage.clear();
  vi.unstubAllGlobals();
});
afterAll(() => server.close());
