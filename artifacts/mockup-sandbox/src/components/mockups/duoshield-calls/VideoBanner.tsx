import { ArrowLeft, Volume2, Video, FlipHorizontal2, SwitchCamera, Mic, MessageSquare, PhoneOff, MessageCircle } from "lucide-react";

function PhoneFrame({ children }: { children: React.ReactNode }) {
  return (
    <div style={{
      minHeight: "100vh", background: "#111318",
      display: "flex", alignItems: "center", justifyContent: "center",
      fontFamily: "'Inter', sans-serif", padding: "32px 0"
    }}>
      <div style={{
        width: "330px", height: "715px", borderRadius: "46px",
        background: "#1a1a1e",
        boxShadow: "0 0 0 1.5px #2a2a30, 0 0 0 10px #0e0e11, 0 0 0 11.5px #2a2a30, 0 40px 80px rgba(0,0,0,0.8)",
        position: "relative", overflow: "hidden", flexShrink: 0
      }}>
        <div style={{ position: "absolute", right: "-3px", top: "120px", width: "3px", height: "60px", background: "#2a2a30", borderRadius: "0 3px 3px 0" }} />
        <div style={{ position: "absolute", left: "-3px", top: "100px", width: "3px", height: "40px", background: "#2a2a30", borderRadius: "3px 0 0 3px" }} />
        <div style={{ position: "absolute", left: "-3px", top: "154px", width: "3px", height: "40px", background: "#2a2a30", borderRadius: "3px 0 0 3px" }} />
        <div style={{ position: "absolute", left: "-3px", top: "208px", width: "3px", height: "40px", background: "#2a2a30", borderRadius: "3px 0 0 3px" }} />
        <div style={{
          position: "absolute", inset: 0, borderRadius: "46px", overflow: "hidden"
        }}>
          <div style={{
            position: "absolute", top: "14px", left: "50%", transform: "translateX(-50%)",
            width: "12px", height: "12px", borderRadius: "50%",
            background: "#0a0a0a", zIndex: 50,
            boxShadow: "0 0 0 1px rgba(255,255,255,0.06)"
          }} />
          {children}
        </div>
      </div>
    </div>
  );
}

function IconBtn({ icon, size = 56, bg = "rgba(255,255,255,0.16)", glowColor }: {
  icon: React.ReactNode; size?: number; bg?: string; glowColor?: string;
}) {
  return (
    <div style={{
      width: `${size}px`, height: `${size}px`, borderRadius: "50%",
      background: bg, display: "flex", alignItems: "center", justifyContent: "center",
      backdropFilter: "blur(14px)",
      boxShadow: glowColor ? `0 8px 24px ${glowColor}` : "0 2px 12px rgba(0,0,0,0.35)",
      flexShrink: 0, cursor: "pointer"
    }}>{icon}</div>
  );
}

export function VideoBanner() {
  return (
    <PhoneFrame>
      <div style={{
        width: "100%", height: "100%", position: "relative", overflow: "hidden",
        fontFamily: "'Inter', sans-serif",
        background: "linear-gradient(175deg, #0c1e34 0%, #081828 40%, #0a1c2e 70%, #060f1a 100%)"
      }}>
        {/* Remote video glow */}
        <div style={{
          position: "absolute", top: "30%", left: "50%", transform: "translate(-50%, -50%)",
          width: "260px", height: "380px",
          background: "radial-gradient(ellipse, rgba(10,40,70,0.8) 0%, transparent 70%)",
          filter: "blur(30px)"
        }} />

        {/* ── Header overlay ── */}
        <div style={{
          position: "absolute", top: 0, left: 0, right: 0, zIndex: 10,
          background: "linear-gradient(to bottom, rgba(6,10,18,0.95) 0%, rgba(6,10,18,0.6) 70%, transparent 100%)",
          paddingBottom: "28px"
        }}>
          {/* Status bar */}
          <div style={{
            display: "flex", justifyContent: "space-between", alignItems: "center",
            padding: "16px 24px 4px", color: "rgba(255,255,255,0.9)", fontSize: "13px", fontWeight: 600
          }}>
            <span>9:41</span>
            <div style={{ display: "flex", gap: "5px", alignItems: "center" }}>
              <svg width="17" height="12" viewBox="0 0 17 12" fill="white">
                <rect x="0" y="6" width="3" height="6" rx="1" opacity={0.4}/>
                <rect x="4.5" y="4" width="3" height="8" rx="1" opacity={0.7}/>
                <rect x="9" y="2" width="3" height="10" rx="1" opacity={0.9}/>
                <rect x="13.5" y="0" width="3" height="12" rx="1"/>
              </svg>
              <svg width="26" height="13" viewBox="0 0 26 13" fill="none">
                <rect x="0.5" y="0.5" width="22" height="12" rx="3.5" stroke="white" strokeOpacity={0.4}/>
                <rect x="2" y="2" width="17" height="9" rx="2" fill="white"/>
              </svg>
            </div>
          </div>

          {/* Header row */}
          <div style={{ display: "flex", alignItems: "center", padding: "2px 6px 0" }}>
            <div style={{ width: "44px", height: "44px", display: "flex", alignItems: "center", justifyContent: "center" }}>
              <ArrowLeft size={20} color="rgba(255,255,255,0.9)" />
            </div>
            <div style={{ flex: 1, textAlign: "center" }}>
              <div style={{ fontSize: "16px", fontWeight: 700, color: "#FFFFFF", letterSpacing: "-0.3px" }}>Alex Rivera</div>
              <div style={{ fontSize: "13px", color: "rgba(255,255,255,0.8)", fontWeight: 600, marginTop: "1px" }}>02:47</div>
            </div>
            <div style={{ width: "44px", height: "44px", display: "flex", alignItems: "center", justifyContent: "center" }}>
              <Volume2 size={20} color="rgba(255,255,255,0.9)" />
            </div>
          </div>

          {/* ── Message banner pill ── */}
          <div style={{
            display: "flex", justifyContent: "center", marginTop: "10px"
          }}>
            <div style={{
              display: "flex", alignItems: "center", gap: "8px",
              background: "rgba(10,14,22,0.88)",
              border: "1px solid rgba(0,201,224,0.3)",
              backdropFilter: "blur(16px)",
              borderRadius: "24px",
              padding: "9px 16px",
              boxShadow: "0 6px 28px rgba(0,0,0,0.55), 0 0 0 0.5px rgba(0,201,224,0.12)",
              maxWidth: "280px"
            }}>
              <MessageCircle size={15} color="#00C9E0" strokeWidth={2.2} style={{ flexShrink: 0 }} />
              <span style={{ fontSize: "13px", color: "rgba(255,255,255,0.9)", fontWeight: 500, whiteSpace: "nowrap" }}>
                Hey, are you seeing this? 👋
              </span>
            </div>
          </div>
        </div>

        {/* ── PiP ── */}
        <div style={{
          position: "absolute", right: "14px", bottom: "124px", zIndex: 10,
          width: "96px", height: "136px", borderRadius: "14px", overflow: "hidden",
          background: "linear-gradient(145deg, #162435 0%, #0d1b2a 100%)",
          boxShadow: "0 4px 20px rgba(0,0,0,0.6), 0 0 0 1.5px rgba(0,201,224,0.25)"
        }}>
          <div style={{
            width: "100%", height: "100%",
            display: "flex", alignItems: "center", justifyContent: "center"
          }}>
            <div style={{
              width: "42px", height: "42px", borderRadius: "50%",
              background: "linear-gradient(135deg, #00C9E0, #0B3A50)",
              display: "flex", alignItems: "center", justifyContent: "center",
              fontSize: "18px", fontWeight: 700, color: "white", opacity: 0.65
            }}>Y</div>
          </div>
          <div style={{
            position: "absolute", bottom: 0, left: 0, right: 0,
            background: "rgba(0,0,0,0.6)", padding: "3px 7px 5px",
            display: "flex", alignItems: "center", justifyContent: "space-between"
          }}>
            <span style={{ fontSize: "10px", color: "rgba(255,255,255,0.85)", fontWeight: 500 }}>You</span>
            <FlipHorizontal2 size={12} color="rgba(255,255,255,0.8)" />
          </div>
        </div>

        {/* ── Controls bar ── */}
        <div style={{
          position: "absolute", bottom: 0, left: 0, right: 0, zIndex: 10,
          background: "linear-gradient(to top, rgba(6,10,18,1) 0%, rgba(6,10,18,0.9) 60%, transparent 100%)",
          paddingBottom: "30px", paddingTop: "18px"
        }}>
          <div style={{
            display: "flex", justifyContent: "space-around", alignItems: "center",
            paddingLeft: "10px", paddingRight: "10px"
          }}>
            <IconBtn icon={<Video size={22} color="white" strokeWidth={2} />} />
            <IconBtn icon={<SwitchCamera size={22} color="white" strokeWidth={2} />} />
            <IconBtn icon={<Mic size={22} color="white" strokeWidth={2} />} />
            {/* Chat button lit up in accent */}
            <IconBtn
              bg="rgba(0,201,224,0.2)"
              icon={<MessageSquare size={22} color="#00C9E0" strokeWidth={2} />}
            />
            <IconBtn
              size={68}
              bg="#E53935"
              glowColor="rgba(229,57,53,0.45)"
              icon={<PhoneOff size={28} color="white" strokeWidth={2} />}
            />
          </div>
          {/* Home indicator */}
          <div style={{
            width: "120px", height: "5px", borderRadius: "3px",
            background: "rgba(255,255,255,0.2)", margin: "14px auto 0"
          }} />
        </div>
      </div>
    </PhoneFrame>
  );
}
