import { useEffect, useRef, useState } from "react";
import { Client, type IMessage } from "@stomp/stompjs";
import SockJS from "sockjs-client";

/**
 * Subscribes to a STOMP topic over SockJS and returns the latest message
 * parsed as JSON. Used for the live balance/transaction feed and the fraud
 * dashboard - both push updates the instant something happens server-side,
 * no polling.
 */
export function useStompTopic<T>(wsUrl: string, topic: string | null) {
  const [lastMessage, setLastMessage] = useState<T | null>(null);
  const [connected, setConnected] = useState(false);
  const clientRef = useRef<Client | null>(null);

  useEffect(() => {
    if (!topic) return;

    const client = new Client({
      webSocketFactory: () => new SockJS(wsUrl) as WebSocket,
      reconnectDelay: 3000,
      onConnect: () => {
        setConnected(true);
        client.subscribe(topic, (message: IMessage) => {
          try {
            setLastMessage(JSON.parse(message.body) as T);
          } catch {
            /* ignore malformed frame */
          }
        });
      },
      onDisconnect: () => setConnected(false),
    });

    client.activate();
    clientRef.current = client;

    return () => {
      client.deactivate();
    };
  }, [wsUrl, topic]);

  return { lastMessage, connected };
}
