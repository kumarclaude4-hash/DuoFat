import { ArrowLeft, Volume2, Video, FlipHorizontal2, Mic, MessageSquare, PhoneOff } from "lucide-react";

function IconBtn({ children, size = 58, color = "rgba(255,255,255,0.18)", shadow }: {
  children: React.ReactNode; size?: number; color?: string; shadow?: string;
}) {
  return (
    <div style={{
      width: `${size}px`, height: `${size}px`, borderRadius: "50%",
      background: color, display: "flex", alignItems: "center", justifyContent: "center",
      backdropFilter: "blur(12px)", boxShadow: shadow || "0 4px 16px rgba(0,0,0,0.3)",
      cursor: "pointer", flexShrink: 0
    }}>{children}</div>
  );
}

export function VideoCall() {
  return (
    <div style={{
      width: "100%", height: "100vh", position: "relative", overflow: "hidden",
      fontFamily: "'Inter', sans-serif",
      background: "linear-gradient(160deg, #0a1628 0%, #081420 30%, #0d1e32 60%, #071218 100%)"
    }}>
      {/* Fake remote video — blurred portrait shape */}
      <div style={{
        position: "absolute", inset: 0,
        background: "linear-gradient(175deg, #102236 0%, #0a1a2a 35%, #0e2030 65%, #091828 100%)"
      }} />
      {/* Remote person silhouette hint */}
      <div style={{
        position: "absolute", bottom: "130px", left: "50%", transform: "translateX(-50%)",
        width: "180px", height: "260px", borderRadius: "90px 90px 0 0",
        background: "rgba(0,201,224,0.04)", filter: "blur(20px)"
      }} />

      {/* ── Header overlay ── */}
      <div style={{
        position: "absolute", top: 0, left: 0, right: 0, zIndex: 10,
        background: "linear-gradient(to bottom, rgba(8,12,20,0.92) 0%, rgba(8,12,20,0.55) 75%, transparent 100%)",
        padding: "14px 8px 28px"
      }}>
        {/* Status bar */}
        <div style={{
          display: "flex", justifyContent: "space-between", alignItems: "center",
          padding: "0 16px 8px", color: "rgba(255,255,255,0.85)", fontSize: "13px", fontWeight: 600
        }}>
          <span>9:41</span>
          <div style={{ display: "flex", gap: "6px", alignItems: "center" }}>
            <svg width="16" height="12" viewBox="0 0 16 12" fill="white" opacity={0.85}>
              <rect x="0" y="4" width="3" height="8" rx="0.8"/>
              <rect x="4.5" y="2.5" width="3" height="9.5" rx="0.8"/>
              <rect x="9" y="0.5" width="3" height="11.5" rx="0.8"/>
            </svg>
            <svg width="25" height="12" viewBox="0 0 25 12" fill="none" opacity={0.85}>
              <rect x="0.5" y="0.5" width="21" height="11" rx="3.5" stroke="white" strokeOpacity="0.4"/>
              <rect x="2" y="2" width="16" height="8" rx="2" fill="white"/>
            </svg>
          </div>
        </div>

        {/* Header row */}
        <div style={{ display: "flex", alignItems: "center", padding: "0 4px" }}>
          <div style={{ width: "48px", height: "48px", display: "flex", alignItems: "center", justifyContent: "center" }}>
            <ArrowLeft size={22} color="white" />
          </div>
          <div style={{ flex: 1, textAlign: "center" }}>
            <div style={{ fontSize: "17px", fontWeight: 700, color: "#FFFFFF", letterSpacing: "-0.3px" }}>Alex Rivera</div>
            <div style={{ fontSize: "14px", color: "rgba(255,255,255,0.85)", fontWeight: 600, marginTop: "1px" }}>02:14</div>
          </div>
          <div style={{ width: "48px", height: "48px", display: "flex", alignItems: "center", justifyContent: "center" }}>
            <Volume2 size={22} color="white" />
          </div>
        </div>
      </div>

      {/* ── Local video PiP ── */}
      <div style={{
        position: "absolute", right: "16px", bottom: "138px", zIndex: 10,
        width: "108px", height: "152px", borderRadius: "14px", overflow: "hidden",
        background: "linear-gradient(145deg, #1a2d42 0%, #0f1f30 100%)",
        boxShadow: "0 4px 20px rgba(0,0,0,0.5), 0 0 0 1.5px rgba(0,201,224,0.3)"
      }}>
        {/* Simulated local video content */}
        <div style={{
          width: "100%", height: "100%",
          background: "linear-gradient(145deg, #152030 0%, #0e1a28 100%)",
          display: "flex", alignItems: "center", justifyContent: "center"
        }}>
          <div style={{
            width: "48px", height: "48px", borderRadius: "50%",
            background: "linear-gradient(135deg, #00C9E0, #0B3A50)",
            display: "flex", alignItems: "center", justifyContent: "center",
            fontSize: "20px", fontWeight: 700, color: "white", opacity: 0.7
          }}>Y</div>
        </div>
        {/* PiP bottom bar */}
        <div style={{
          position: "absolute", bottom: 0, left: 0, right: 0,
          background: "rgba(0,0,0,0.55)", padding: "4px 8px 6px",
          display: "flex", alignItems: "center", justifyContent: "space-between"
        }}>
          <span style={{ fontSize: "11px", color: "rgba(255,255,255,0.85)", fontWeight: 500 }}>You</span>
          <FlipHorizontal2 size={14} color="rgba(255,255,255,0.8)" />
        </div>
      </div>

      {/* ── Controls bar ── */}
      <div style={{
        position: "absolute", bottom: 0, left: 0, right: 0, zIndex: 10,
        background: "linear-gradient(to top, rgba(8,12,20,0.98) 0%, rgba(8,12,20,0.88) 65%, transparent 100%)",
        paddingBottom: "36px", paddingTop: "20px"
      }}>
        <div style={{
          display: "flex", justifyContent: "space-around", alignItems: "center",
          paddingLeft: "8px", paddingRight: "8px"
        }}>
          {/* Camera */}
          <IconBtn><Video size={24} color="white" strokeWidth={2} /></IconBtn>
          {/* Flip */}
          <IconBtn><FlipHorizontal2 size={24} color="white" strokeWidth={2} /></IconBtn>
          {/* Mute */}
          <IconBtn><Mic size={24} color="white" strokeWidth={2} /></IconBtn>
          {/* Chat */}
          <IconBtn><MessageSquare size={24} color="white" strokeWidth={2} /></IconBtn>
          {/* End call */}
          <IconBtn size={72} color="#E53935" shadow="0 8px 24px rgba(229,57,53,0.45)">
            <PhoneOff size={30} color="white" strokeWidth={2} />
          </IconBtn>
        </div>
      </div>
    </div>
  );
}
