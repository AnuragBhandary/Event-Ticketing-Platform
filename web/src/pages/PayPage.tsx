import { useNavigate, useParams, useSearchParams } from "react-router";

import { useGatewayOrder, usePay } from "../api/queries";
import { ErrorNote, Loading } from "../components/States";
import { money } from "../lib/format";

/**
 * The fake gateway's checkout page. With a real gateway this would be Razorpay's own checkout;
 * here it lets you choose the outcome. Either way the booking is only confirmed by the gateway's
 * signed webhook to the API, never by this page.
 */
export function PayPage() {
  const { orderId = "" } = useParams();
  const [params] = useSearchParams();
  const holdId = params.get("hold");
  const navigate = useNavigate();
  const order = useGatewayOrder(orderId);
  const pay = usePay();

  if (order.error) return <ErrorNote error={order.error} />;
  if (!order.data) return <Loading />;
  const done = () => void navigate(holdId ? `/holds/${holdId}` : "/bookings");

  return (
    <div className="mx-auto max-w-sm">
      <section className="overflow-hidden rounded-2xl bg-white shadow-lg ring-1 ring-stone-200 dark:bg-stone-900 dark:ring-stone-800" aria-label="FakePay checkout">
        <div className="bg-indigo-700 px-5 py-3 text-white">
          <p className="text-xs font-semibold uppercase tracking-widest opacity-80">FakePay · test mode</p>
          <p className="text-sm">{order.data.merchant}</p>
        </div>
        <div className="space-y-4 p-5">
          <p className="text-3xl font-bold tabular-nums" data-testid="pay-amount">{money(order.data.amountPaise)}</p>
          <p className="font-mono text-xs text-stone-500">{order.data.orderId}</p>
          {order.data.status === "PAID" ? (
            <p className="text-sm text-emerald-700">This order is already paid.</p>
          ) : (
            <div className="grid gap-2">
              <button type="button" className="btn bg-indigo-700 text-white hover:bg-indigo-600" disabled={pay.isPending}
                onClick={() => pay.mutate({ orderId, outcome: "success" }, { onSuccess: done })}>
                Pay {money(order.data.amountPaise)}
              </button>
              <button type="button" className="btn btn-secondary" disabled={pay.isPending}
                onClick={() => pay.mutate({ orderId, outcome: "failure" }, { onSuccess: done })}>
                Simulate a failed payment
              </button>
            </div>
          )}
          {pay.error && <ErrorNote error={pay.error} />}
          <p className="text-xs text-stone-500">No real money moves. The gateway confirms the result to the API with a signed webhook.</p>
        </div>
      </section>
    </div>
  );
}
