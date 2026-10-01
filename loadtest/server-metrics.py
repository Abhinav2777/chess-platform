#!/usr/bin/env python3
"""Server-side numbers for a load test on kind (Phase 9.2).

Reads each API pod's /actuator/prometheus through the Kubernetes API server's pod proxy
(pods/<pod>:8081/proxy/...) — the management port is not exposed, and needs no port-forward.

  server-metrics.py snapshot <out.json>             counters/histograms at one instant
  server-metrics.py sample <seconds> <out.jsonl>    gauges every 5 s (pool, CPU) + kubectl top
  server-metrics.py report <before> <after> <samples.jsonl>

Latency percentiles come from histogram BUCKET DELTAS between two snapshots — what Prometheus's
histogram_quantile does over a range — so they cover exactly the test window, per message type.
"""
import json, re, subprocess, sys, time

NS = "chess"
LINE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{([^}]*)\})?\s+([-+0-9.eE]+|NaN|\+Inf|-Inf)$')


def pods():
    """Ready, non-terminating API pods. A terminating pod still reports phase Running — a field
    selector on phase would scrape pods that are draining (or run an older image)."""
    items = json.loads(subprocess.check_output(
        ["kubectl", "-n", NS, "get", "pods", "-l", "app.kubernetes.io/name=api", "-o", "json"], text=True))["items"]
    return [i["metadata"]["name"] for i in items
            if not i["metadata"].get("deletionTimestamp")
            and any(c["type"] == "Ready" and c["status"] == "True" for c in i["status"].get("conditions", []))]


def scrape(pod):
    text = subprocess.check_output(["kubectl", "get", "--raw",
                                    f"/api/v1/namespaces/{NS}/pods/{pod}:8081/proxy/actuator/prometheus"], text=True)
    series = {}
    for line in text.splitlines():
        m = LINE.match(line)
        if m:
            series[m.group(1) + "{" + (m.group(3) or "") + "}"] = float(m.group(4))
    return series


def labels(key):
    return dict(re.findall(r'(\w+)="([^"]*)"', key[key.index("{"):]))


def snapshot(path):
    json.dump({"t": time.time(), "restarts": restarts(), "pods": {p: scrape(p) for p in pods()}}, open(path, "w"))


def gauge(series, name, **want):
    vals = [v for k, v in series.items() if k.startswith(name + "{")
            and all(labels(k).get(a) == b for a, b in want.items())]
    return max(vals) if vals else None


def total(series, name, **want):
    return sum(v for k, v in series.items() if k.startswith(name + "{")
               and all(labels(k).get(a) == b for a, b in want.items()))


def restarts():
    items = json.loads(subprocess.check_output(
        ["kubectl", "-n", NS, "get", "pods", "-l", "app.kubernetes.io/name=api", "-o", "json"], text=True))["items"]
    return {i["metadata"]["name"]: sum(c.get("restartCount", 0) for c in i["status"].get("containerStatuses", []))
            for i in items}


def top():
    try:
        out = subprocess.check_output(["kubectl", "-n", NS, "top", "pods", "--no-headers"], text=True)
        return {l.split()[0]: {"cpu_m": int(l.split()[1].rstrip("m")), "mem_mi": int(l.split()[2].rstrip("Mi"))}
                for l in out.splitlines() if l.strip()}
    except Exception:
        return {}


def sample(seconds, path):
    end = time.time() + seconds
    with open(path, "w") as f:
        while time.time() < end:
            row = {"t": time.time(), "pods": {}, "top": top()}
            for p in pods():
                try:
                    s = scrape(p)
                except subprocess.CalledProcessError:
                    continue
                row["pods"][p] = {
                    "pending": gauge(s, "hikaricp_connections_pending"),
                    "active": gauge(s, "hikaricp_connections_active"),
                    "max": gauge(s, "hikaricp_connections_max"),
                    "process_cpu": gauge(s, "process_cpu_usage"),
                    "cpus": gauge(s, "system_cpu_count"),
                    # Where the container's memory goes (9.3): the heap is bounded by
                    # MaxRAMPercentage; non-heap and native are not.
                    "heap_used_mi": total(s, "jvm_memory_used_bytes", area="heap") / 2**20,
                    "heap_committed_mi": total(s, "jvm_memory_committed_bytes", area="heap") / 2**20,
                    "nonheap_used_mi": total(s, "jvm_memory_used_bytes", area="nonheap") / 2**20,
                    "threads": gauge(s, "jvm_threads_live_threads"),
                }
            f.write(json.dumps(row) + "\n"); f.flush()
            time.sleep(5)


def quantile(buckets, q):
    """buckets: [(le, cumulative_count)] sorted, last le = inf. Linear within the bucket."""
    total = buckets[-1][1]
    if total <= 0:
        return None
    rank, prev_le, prev_c = q * total, 0.0, 0.0
    for le, c in buckets:
        if c >= rank:
            if le == float("inf"):
                return prev_le
            return prev_le + (le - prev_le) * ((rank - prev_c) / (c - prev_c) if c > prev_c else 0)
        prev_le, prev_c = le, c
    return prev_le


def report(before_path, after_path, samples_path):
    before, after = json.load(open(before_path)), json.load(open(after_path))
    window = after["t"] - before["t"]
    print(f"window: {window:.0f} s, pods: {', '.join(sorted(after['pods']))}")
    rb, ra = before.get("restarts", {}), after.get("restarts", {})
    print("api container restarts during the window:",
          {p[-5:]: ra[p] - rb.get(p, 0) for p in ra} if ra else "n/a")
    # Per message type, all pods together: bucket deltas summed across pods.
    merged = {}
    for pod, a in after["pods"].items():
        b = before["pods"].get(pod, {})
        for k, v in a.items():
            if k.startswith("chess_ws_message_seconds_bucket{"):
                lab = labels(k)
                if lab.get("error", "none") != "none":
                    continue
                le = float("inf") if lab["le"] == "+Inf" else float(lab["le"])
                merged.setdefault(lab["type"], {}).setdefault(le, 0.0)
                merged[lab["type"]][le] += v - b.get(k, 0.0)
    print("server-side chess.ws.message (successful frames), ms:")
    for mtype in sorted(merged):
        bk = sorted(merged[mtype].items())
        n = bk[-1][1]
        if n <= 0:
            continue
        p = [quantile(bk, q) for q in (0.5, 0.95, 0.99)]
        print(f"  {mtype:12} n={n:7.0f} ({n / window:6.1f}/s)  p50={p[0]*1000:7.2f}  p95={p[1]*1000:7.2f}  p99={p[2]*1000:7.2f}")
    # Connection pool: how long requests WAITED for a JDBC connection.
    acq_n = acq_s = 0.0
    for pod, a in after["pods"].items():
        b = before["pods"].get(pod, {})
        for k, v in a.items():
            if k.startswith("hikaricp_connections_acquire_seconds_count{"):
                acq_n += v - b.get(k, 0.0)
            if k.startswith("hikaricp_connections_acquire_seconds_sum{"):
                acq_s += v - b.get(k, 0.0)
    rows = [json.loads(l) for l in open(samples_path) if l.strip()]
    pend = [r2["pending"] for r in rows for r2 in r["pods"].values() if r2.get("pending") is not None]
    act = [r2["active"] for r in rows for r2 in r["pods"].values() if r2.get("active") is not None]
    mx = [r2["max"] for r in rows for r2 in r["pods"].values() if r2.get("max") is not None]
    if acq_n:
        print(f"hikari: {acq_n:.0f} acquisitions, mean wait {acq_s / acq_n * 1000:.3f} ms; "
              f"pending max {max(pend or [0]):.0f}; active max {max(act or [0]):.0f} of {max(mx or [0]):.0f}")
    cpu = {}
    for r in rows:
        for pod, t in r.get("top", {}).items():
            cpu.setdefault(pod, []).append(t)
    mem = {}
    for r in rows:
        for pod, v in r["pods"].items():
            if v.get("heap_used_mi") is not None:
                mem.setdefault(pod, []).append(v)
    for pod in sorted(mem):
        m = mem[pod]
        print(f"  jvm {pod[-5:]}: heap used max {max(x['heap_used_mi'] for x in m):5.0f} Mi, committed max "
              f"{max(x['heap_committed_mi'] for x in m):5.0f} Mi, non-heap max {max(x['nonheap_used_mi'] for x in m):5.0f} Mi, "
              f"threads max {max((x['threads'] or 0) for x in m):4.0f}")
    print("kubectl top during the window (CPU millicores avg/max, memory Mi max):")
    for pod in sorted(cpu):
        c = [t["cpu_m"] for t in cpu[pod]]; m = [t["mem_mi"] for t in cpu[pod]]
        print(f"  {pod:34} cpu {sum(c)/len(c):6.0f} / {max(c):5d}m   mem {max(m):5d} Mi")


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "snapshot": snapshot(sys.argv[2])
    elif cmd == "sample": sample(float(sys.argv[2]), sys.argv[3])
    elif cmd == "report": report(sys.argv[2], sys.argv[3], sys.argv[4])
    else: sys.exit(__doc__)
