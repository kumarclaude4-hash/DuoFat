import { useState, useEffect, useRef } from "react";
import "./loaders2.css";

/* ─── 1. Fingerprint Scan ────────────────────────────────────
   A scan line sweeps top→bottom, illuminating ridge lines     */
function FingerprintScan() {
  return (
    <div className="loader-card">
      <div className="fp-wrap">
        <svg viewBox="0 0 120 140" width="90" height="105" className="fp-svg">
          <defs>
            <linearGradient id="fpScan" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%"   stopColor="var(--accent)" stopOpacity="0"/>
              <stop offset="45%"  stopColor="var(--accent)" stopOpacity="0"/>
              <stop offset="50%"  stopColor="var(--accent)" stopOpacity="1"/>
              <stop offset="55%"  stopColor="var(--accent)" stopOpacity="0"/>
              <stop offset="100%" stopColor="var(--accent)" stopOpacity="0"/>
            </linearGradient>
            <mask id="fpMask">
              <rect x="0" y="0" width="120" height="140" fill="white"/>
            </mask>
          </defs>
          {/* Fingerprint ridges — concentric arcs */}
          {[
            "M 60 10 C 30 10 10 30 10 60 C 10 95 35 118 60 128 C 85 118 110 95 110 60 C 110 30 90 10 60 10 Z",
            "M 60 22 C 38 22 20 38 20 60 C 20 88 40 108 60 118 C 80 108 100 88 100 60 C 100 38 82 22 60 22 Z",
            "M 60 34 C 45 34 30 46 30 60 C 30 80 44 98 60 108 C 76 98 90 80 90 60 C 90 46 75 34 60 34 Z",
            "M 60 46 C 50 46 40 54 40 60 C 40 74 50 87 60 96 C 70 87 80 74 80 60 C 80 54 70 46 60 46 Z",
            "M 60 57 C 55 57 50 59 50 62 C 50 68 55 74 60 78 C 65 74 70 68 70 62 C 70 59 65 57 60 57 Z",
          ].map((d, i) => (
            <path key={i} d={d} fill="none" stroke="var(--ridge)" strokeWidth="2.2" strokeLinecap="round" opacity={0.9 - i * 0.05}/>
          ))}
          {/* Animated scan overlay */}
          <rect x="0" y="0" width="120" height="140" fill="url(#fpScan)" className="fp-scan-rect"/>
          {/* Corner brackets */}
          {[
            [[8,8],[8,22],[8,8],[22,8]],
            [[112,8],[98,8],[112,8],[112,22]],
            [[8,132],[8,118],[8,132],[22,132]],
            [[112,132],[98,132],[112,132],[112,118]],
          ].map((bracket, bi) => (
            <g key={bi}>
              <line x1={bracket[0][0]} y1={bracket[0][1]} x2={bracket[1][0]} y2={bracket[1][1]} stroke="var(--accent)" strokeWidth="2.5" strokeLinecap="round"/>
              <line x1={bracket[2][0]} y1={bracket[2][1]} x2={bracket[3][0]} y2={bracket[3][1]} stroke="var(--accent)" strokeWidth="2.5" strokeLinecap="round"/>
            </g>
          ))}
        </svg>
      </div>
      <span className="loader-label">Fingerprint Scan</span>
      <span className="loader-sub">Verifying identity…</span>
    </div>
  );
}

/* ─── 2. Matrix Rain ─────────────────────────────────────────
   Columns of falling glyphs in accent colour, fading out      */
function MatrixRain() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const canvas = canvasRef.current!;
    const ctx = canvas.getContext("2d")!;
    const W = canvas.width, H = canvas.height;
    const cols = Math.floor(W / 14);
    const drops = Array.from({ length: cols }, () => Math.random() * -H);
    const chars = "アイウエオカキクケコ01アβΣΩ#$%&10ΨΔ∇≡≈";

    // Read accent from CSS variable
    const accent = getComputedStyle(canvas).getPropertyValue("--accent").trim() || "#7C6BFF";

    let raf: number;
    const draw = () => {
      ctx.fillStyle = "rgba(0,0,0,0.13)";
      ctx.fillRect(0, 0, W, H);
      ctx.font = "bold 12px monospace";
      drops.forEach((y, i) => {
        const char = chars[Math.floor(Math.random() * chars.length)];
        // Head glyph bright, rest fade
        ctx.fillStyle = "#fff";
        ctx.globalAlpha = 0.9;
        ctx.fillText(char, i * 14 + 1, y);
        ctx.fillStyle = accent;
        ctx.globalAlpha = 0.55;
        ctx.fillText(chars[Math.floor(Math.random() * chars.length)], i * 14 + 1, y - 14);
        ctx.globalAlpha = 0.25;
        ctx.fillText(chars[Math.floor(Math.random() * chars.length)], i * 14 + 1, y - 28);
        ctx.globalAlpha = 1;
        drops[i] += 14;
        if (drops[i] > H + 20 && Math.random() > 0.96) drops[i] = -20;
      });
      raf = requestAnimationFrame(draw);
    };
    draw();
    return () => cancelAnimationFrame(raf);
  }, []);

  return (
    <div className="loader-card">
      <div className="mx-wrap">
        <canvas ref={canvasRef} width={108} height={88} className="mx-canvas"/>
      </div>
      <span className="loader-label">Matrix Rain</span>
      <span className="loader-sub">Securing channel…</span>
    </div>
  );
}

/* ─── 3. Waveform Pulse ──────────────────────────────────────
   Equaliser bars animate like an audio signal                  */
function WaveformPulse() {
  const bars = [0.4, 0.7, 0.5, 1, 0.6, 0.85, 0.45, 0.9, 0.55, 0.75, 0.5, 0.65];
  return (
    <div className="loader-card">
      <div className="wf-wrap">
        {bars.map((h, i) => (
          <div
            key={i}
            className="wf-bar"
            style={{ animationDelay: `${i * 0.08}s`, "--bar-h": h } as React.CSSProperties}
          />
        ))}
      </div>
      <span className="loader-label">Waveform Pulse</span>
      <span className="loader-sub">Transmitting audio…</span>
    </div>
  );
}

/* ─── 4. Decrypt Grid ────────────────────────────────────────
   3×3 grid of chars scramble then lock into ✓ symbols         */
function DecryptGrid() {
  const symbols = "!@#$%^&*?Ω∑∂≈≡ΨΔβ01";
  const [cells, setCells] = useState<string[]>(Array(9).fill("?"));
  const [locked, setLocked] = useState<boolean[]>(Array(9).fill(false));

  useEffect(() => {
    const order = [4, 0, 8, 2, 6, 1, 3, 5, 7]; // lock order
    let lockIdx = 0;
    const scramble = setInterval(() => {
      setCells(c => c.map((v, i) => locked[i] ? v : symbols[Math.floor(Math.random() * symbols.length)]));
    }, 80);

    const lockNext = setInterval(() => {
      if (lockIdx >= order.length) { clearInterval(lockNext); return; }
      const idx = order[lockIdx++];
      setLocked(l => { const n = [...l]; n[idx] = true; return n; });
      setCells(c => { const n = [...c]; n[idx] = "✓"; return n; });
    }, 320);

    // After all locked, reset
    const reset = setTimeout(() => {
      setLocked(Array(9).fill(false));
      setCells(Array(9).fill("?"));
      lockIdx = 0;
    }, order.length * 320 + 600);

    return () => { clearInterval(scramble); clearInterval(lockNext); clearTimeout(reset); };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [locked.every(Boolean)]);

  return (
    <div className="loader-card">
      <div className="dg-wrap">
        {cells.map((ch, i) => (
          <div key={i} className={`dg-cell ${locked[i] ? "dg-locked" : "dg-scramble"}`}>
            {ch}
          </div>
        ))}
      </div>
      <span className="loader-label">Decrypt Grid</span>
      <span className="loader-sub">Decrypting payload…</span>
    </div>
  );
}

/* ─── Showcase ───────────────────────────────────────────── */
export function LoadingShowcase2() {
  const [dark, setDark] = useState(true);
  return (
    <div className={`showcase-root2 ${dark ? "theme-dark" : "theme-light"}`}>
      <div className="showcase-header">
        <span className="showcase-title">Loaders Vol.2</span>
        <button className="toggle-btn" onClick={() => setDark(d => !d)}>
          {dark
            ? <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/></svg>
            : <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>
          }
          {dark ? "Light" : "Dark"}
        </button>
      </div>
      <div className="showcase-grid">
        <FingerprintScan/>
        <MatrixRain/>
        <WaveformPulse/>
        <DecryptGrid/>
      </div>
    </div>
  );
}
