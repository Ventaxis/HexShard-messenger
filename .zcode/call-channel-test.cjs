// A/B test for private call signaling channels (review SEC-001 hardening).
// 1) A and B join their pair topic with private:true -> phx_reply ok
// 2) A broadcasts -> B receives the event
// 3) A joins a foreign pair topic -> denial/timeout
const BASE = "https://zqyipemjvzcxjpglmegy.supabase.co";
const ANON = "sb_publishable_NNLyZlCPko3fbD4ibBjeQw_7OV0OpcS";
const uidOf = (s) => s.user?.id;
const tokenOf = (s) => s.access_token;
const topicFor = (a, b) => "realtime:call_" + [a, b].sort()[0] + "_" + [a, b].sort()[1];

function connect(anon) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(BASE.replace("https", "wss") + "/realtime/v1/websocket?apikey=" + anon + "&vsn=1.0.0");
    const inbox = [];
    const waiters = [];
    ws.onmessage = (m) => {
      const frame = JSON.parse(m.data);
      for (let i = waiters.length - 1; i >= 0; i--) {
        if (waiters[i].match(frame)) { waiters[i].resolve(frame); waiters.splice(i, 1); }
      }
      inbox.push(frame);
    };
    ws.onopen = () => resolve({
      ws,
      wait: (pred, ms = 8000) => new Promise((res, rej) => {
        const hit = inbox.find(pred);
        if (hit) return res(hit);
        const w = { match: pred, resolve: res };
        waiters.push(w);
        setTimeout(() => { const i = waiters.indexOf(w); if (i >= 0) waiters.splice(i, 1); rej(new Error("timeout")); }, ms);
      }),
      send: (f) => ws.send(JSON.stringify(f))
    });
    ws.onerror = (e) => reject(new Error("ws error"));
    setTimeout(() => reject(new Error("ws connect timeout")), 10000);
  });
}
const join = (socket, topic, token, priv) => socket.send({
  topic, event: "phx_join", ref: "j-" + Math.random().toString(36).slice(2, 8),
  join_ref: "j0", payload: { config: { broadcast: { self: false }, presence: { key: "" }, private: priv }, access_token: token }
});
const broadcast = (socket, topic, event, payload) => socket.send({
  topic, event: "broadcast", ref: "b-" + Math.random().toString(36).slice(2, 8),
  join_ref: "j0", payload: { type: "broadcast", event, payload }
});

(async () => {
  const signup = async (tag) => {
    const email = `priv-${tag}-${Date.now()}@example.com`, password = "Ee5!" + Date.now();
    const r = await (await fetch(`${BASE}/auth/v1/signup`, {
      method: "POST", headers: { apikey: ANON, "Content-Type": "application/json" },
      body: JSON.stringify({ email, password })
    })).json();
    if (!r.user?.id) throw new Error("signup failed " + tag);
    return { uid: uidOf(r), token: tokenOf(r) };
  };
  const A = await signup("a"), B = await signup("b"), C = await signup("c");
  const pairTopic = topicFor(A.uid, B.uid);
  const foreignTopic = topicFor(B.uid, C.uid);

  const sockA = await connect(ANON);
  join(sockA, pairTopic, A.token, true);
  const joinA = await sockA.wait((f) => f.event === "phx_reply" && f.topic === pairTopic);
  console.log("A join pair(private):", joinA.payload?.status);

  const sockB = await connect(ANON);
  join(sockB, pairTopic, B.token, true);
  const joinB = await sockB.wait((f) => f.event === "phx_reply" && f.topic === pairTopic);
  console.log("B join pair(private):", joinB.payload?.status);

  broadcast(sockA, pairTopic, "call_signal", { kind: "offer_probe", fromUid: A.uid });
  try {
    const got = await sockB.wait((f) => f.event === "broadcast" && f.topic === pairTopic, 8000);
    console.log("B received broadcast:", got.payload?.payload?.kind === "offer_probe" ? "ok" : "shape mismatch");
  } catch { console.log("B received broadcast: TIMEOUT (delivery broken)"); }

  join(sockA, foreignTopic, A.token, true);
  try {
    const foreign = await sockA.wait((f) => f.event === "phx_reply" && f.topic === foreignTopic);
    console.log("A join foreign topic:", foreign.payload?.status === "ok" ? "ALLOWED (RLS NOT ENFORCED!)" : "denied: " + (foreign.payload?.response?.reason || "err"));
  } catch { console.log("A join foreign topic: denied by timeout (good)"); }

  [sockA, sockB].forEach(s => s.ws && s.ws.close());
  console.log("CLEANUP_UIDS=" + [A.uid, B.uid, C.uid].join(","));
})().catch(e => { console.log("TEST ERR: " + e.message); process.exit(1); });
