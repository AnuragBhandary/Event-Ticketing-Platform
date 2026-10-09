import { expect, it } from "vitest";

import { countdown, money } from "./format";

it("formats paise as Indian rupees", () => {
  expect(money(250_000)).toBe("₹2,500");
  expect(money(12_345_678)).toBe("₹1,23,456.78");
  expect(money(99_950)).toBe("₹999.5");
});

it("counts down in m:ss and never below zero", () => {
  expect(countdown(600_000)).toBe("10:00");
  expect(countdown(61_500)).toBe("1:02");
  expect(countdown(-5)).toBe("0:00");
});
