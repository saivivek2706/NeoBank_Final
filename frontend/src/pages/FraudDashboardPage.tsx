import { useEffect, useState } from "react";
import { fraudApi } from "../api/banking";
import { useStompTopic } from "../api/useStompTopic";
import type { FraudAlert } from "../types";

export function FraudDashboardPage() {
  const [alerts, setAlerts] = useState<FraudAlert[]>([]);
  const { lastMessage, connected } = useStompTopic<FraudAlert>("/ws/fraud", "/topic/fraud-alerts");

  useEffect(() => {
    fraudApi.all().then(setAlerts);
  }, []);

  useEffect(() => {
    if (lastMessage) setAlerts((prev) => [lastMessage, ...prev]);
  }, [lastMessage]);

  return (
    <div>
      <h2 style={{ fontSize: 18, marginBottom: 20 }}>
        {connected && <span className="live-dot" />}
        Fraud watch
      </h2>
      <div className="card">
        <p className="card-title">Flagged transactions ({alerts.length})</p>
        {alerts.length === 0 ? (
          <div className="empty-state">No fraud signals yet. Alerts will appear here the instant a rule fires.</div>
        ) : (
          alerts.map((a) => (
            <div className="ledger-row" key={a.id}>
              <div className="ledger-main">
                <span className={`severity-${a.severity.toLowerCase()}`}>
                  {a.ruleTriggered.replace("_", " ")} · {a.severity}
                </span>
                <span className="ledger-meta">{a.details}</span>
                <span className="ledger-meta">{new Date(a.flaggedAt).toLocaleString()}</span>
              </div>
              <span className="ledger-amount">{a.amount.toFixed(2)}</span>
            </div>
          ))
        )}
      </div>
    </div>
  );
}
