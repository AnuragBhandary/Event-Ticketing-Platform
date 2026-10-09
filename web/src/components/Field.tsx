import { cloneElement, useId, type ReactElement } from "react";

/**
 * A labelled form control with its error message. The message sits outside the <label> and is
 * linked with aria-describedby, so the field's accessible name stays "Email", not
 * "Email Enter a valid email", and screen readers announce the error as a description.
 */
export function Field({ label, error, className = "", children }: {
  label: string;
  error?: string;
  className?: string;
  children: ReactElement<Record<string, unknown>>;
}) {
  const id = useId();
  const errorId = `${id}-error`;
  return (
    <div className={`space-y-1 text-sm ${className}`}>
      <label htmlFor={id} className="block font-medium">{label}</label>
      {cloneElement(children, { id, "aria-invalid": error ? true : undefined, "aria-describedby": error ? errorId : undefined })}
      {error && <p id={errorId} role="alert" className="text-xs text-red-600">{error}</p>}
    </div>
  );
}
