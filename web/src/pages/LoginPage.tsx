import { zodResolver } from "@hookform/resolvers/zod";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { useNavigate, useSearchParams } from "react-router";
import { z } from "zod";

import { login, register as registerUser } from "../api/queries";
import { auth } from "../api/session";
import { Field } from "../components/Field";

const signInSchema = z.object({
  name: z.string(),
  email: z.string().trim().email("Enter a valid email"),
  password: z.string().min(1, "Enter your password"),
});
const signUpSchema = signInSchema.extend({
  name: z.string().trim().min(1, "Tell us your name").max(80),
  password: z.string().min(8, "At least 8 characters").max(72),
});
type Values = z.infer<typeof signInSchema>;

export function LoginPage() {
  const [mode, setMode] = useState<"in" | "up">("in");
  const navigate = useNavigate();
  const next = useSearchParams()[0].get("next");
  const { register, handleSubmit, setError, formState: { errors, isSubmitting } } = useForm<Values>({
    resolver: zodResolver(mode === "up" ? signUpSchema : signInSchema),
    defaultValues: { name: "", email: "", password: "" },
  });

  const onSubmit = handleSubmit(async (v) => {
    try {
      auth.signIn(mode === "up" ? await registerUser(v.name, v.email, v.password) : await login(v.email, v.password));
      void navigate(next?.startsWith("/") ? next : "/", { replace: true }); // only same-site paths
    } catch (e) {
      setError("root", { message: (e as Error).message });
    }
  });

  return (
    <form onSubmit={onSubmit} noValidate className="card mx-auto max-w-sm space-y-4" aria-label={mode === "up" ? "Create account" : "Sign in"}>
      <h1 className="text-xl font-bold">{mode === "up" ? "Create an account" : "Sign in"}</h1>
      {mode === "up" && (
        <Field label="Name" error={errors.name?.message}><input className="field" autoComplete="name" {...register("name")} /></Field>
      )}
      <Field label="Email" error={errors.email?.message}><input className="field" type="email" autoComplete="email" {...register("email")} /></Field>
      <Field label="Password" error={errors.password?.message}>
        <input className="field" type="password" autoComplete={mode === "up" ? "new-password" : "current-password"} {...register("password")} />
      </Field>
      {errors.root && <p role="alert" className="text-sm text-red-600">{errors.root.message}</p>}
      <button type="submit" className="btn btn-primary w-full" disabled={isSubmitting}>{mode === "up" ? "Create account" : "Sign in"}</button>
      <button type="button" className="w-full text-sm text-stone-500 underline" onClick={() => setMode(mode === "up" ? "in" : "up")}>
        {mode === "up" ? "I already have an account" : "New here? Create an account"}
      </button>
    </form>
  );
}
