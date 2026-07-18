import { Phone, PhoneOff, Lock } from "lucide-react";

function PhoneFrame({ children }: { children: React.ReactNode }) {
  return (
    <div style={{
      minHeight: "100vh", background: "#111318",
      display: "flex", alignItems: "center", justifyContent: "center",
      fontFamily: "'Inter', sans-serif", padding: "32px 0"
    }}>
      {/* Outer phone shell */}
      <div style={{
        width: "330px", height: "715px", borderRadius: "46px",
        background: "#1a1a1e",
        boxShadow: "0 0 0 1.5px #2a2a30, 0 0 0 10px #0e0e11, 0 0 0 11.5px #2a2a30, 0 40px 80px rgba(0,0,0,0.8)",
        position: "relative", overflow: "hidden", flexShrink: 0
      }}>
        {/* Side buttons */}
        <div style={{ position: "absolute", right: "-3px", top: "120px", width: "3px", height: "60px", background: "#2a2a30", borderRadius: "0 3px 3px 0" }} />
        <div style={{ position: "absolute", left: "-3px", top: "100px", width: "3px", height: "40px", background: "#2a2a30", borderRadius: "3px 0 0 3px" }} />
        <div style={{ position: "absolute", left: "-3px", top: "154px", width: "3px", height: "40px", background: "#2a2a30", borderRadius: "3px 0 0 3px" }} />
        <div style={{ position: "absolute", left: "-3px", top: "208px", width: "3px", height: "40px", background: "#2a2a30", borderRadius: "3px 0 0 3px" }} />

        {/* Screen area */}
        <div style={{
          position: "absolute", inset: "0",
          borderRadius: "46px",
          overflow: "hidden",
          background: "#0D1118"
        }}>
          {/* Punch-hole camera */}
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

function StatusBar() {
  return (
    <div style={{
      width: "100%", display: "flex", justifyContent: "space-between",
      alignItems: "center", padding: "16px 24px 4px",
      color: "rgba(255,255,255,0.9)", fontSize: "13px", fontWeight: 600
    }}>
      <span>9:41</span>
      <div style={{ display: "flex", gap: "5px", alignItems: "center" }}>
        {/* Signal bars */}
        <svg width="17" height="12" viewBox="0 0 17 12" fill="white">
          <rect x="0" y="6" width="3" height="6" rx="1" opacity={0.4}/>
          <rect x="4.5" y="4" width="3" height="8" rx="1" opacity={0.6}/>
          <rect x="9" y="2" width="3" height="10" rx="1" opacity={0.8}/>
          <rect x="13.5" y="0" width="3" height="12" rx="1"/>
        </svg>
        {/* WiFi */}
        <svg width="16" height="12" viewBox="0 0 16 12" fill="white">
          <path d="M8 9.5L10.5 7a3.5 3.5 0 00-5 0L8 9.5z"/>
          <path d="M8 9.5L12.5 5a6.4 6.4 0 00-9 0L8 9.5z" opacity={0.6}/>
          <path d="M8 9.5L14.5 3a9.2 9.2 0 00-13 0L8 9.5z" opacity={0.3}/>
          <circle cx="8" cy="11" r="1.2"/>
        </svg>
        {/* Battery */}
        <svg width="26" height="13" viewBox="0 0 26 13" fill="none">
          <rect x="0.5" y="0.5" width="22" height="12" rx="3.5" stroke="white" strokeOpacity={0.4}/>
          <rect x="2" y="2" width="17" height="9" rx="2" fill="white"/>
          <path d="M24 4.5v4c1-.5 1.5-1.1 1.5-2s-.5-1.5-1.5-2z" fill="white" opacity={0.4}/>
        </svg>
      </div>
    </div>
  );
}

export function IncomingCall() {
  return (
    <PhoneFrame>
      <div style={{
        width: "100%", height: "100%", background: "#0D1118",
        display: "flex", flexDirection: "column", alignItems: "center",
        overflow: "hidden", position: "relative"
      }}>
        <StatusBar />

        {/* Incoming label */}
        <div style={{
          marginTop: "20px", color: "rgba(255,255,255,0.45)", fontSize: "12px",
          letterSpacing: "0.1em", textTransform: "uppercase", fontWeight: 600
        }}>Incoming call</div>

        {/* Avatar ring + avatar */}
        <div style={{ marginTop: "44px", position: "relative" }}>
          <div style={{
            position: "absolute", inset: "-20px", borderRadius: "50%",
            background: "radial-gradient(circle, rgba(0,201,224,0.10) 0%, transparent 70%)"
          }} />
          <div style={{
            position: "absolute", inset: "-10px", borderRadius: "50%",
            border: "1.5px solid rgba(0,201,224,0.2)"
          }} />
          <div style={{
            width: "124px", height: "124px", borderRadius: "50%",
            background: "linear-gradient(145deg, #00C9E0 0%, #0B3A50 100%)",
            display: "flex", alignItems: "center", justifyContent: "center",
            fontSize: "52px", fontWeight: 700, color: "white",
            boxShadow: "0 0 0 2.5px rgba(0,201,224,0.2), 0 16px 40px rgba(0,0,0,0.5)"
          }}>A</div>
        </div>

        {/* Name */}
        <div style={{
          marginTop: "24px", fontSize: "28px", fontWeight: 700,
          color: "#FFFFFF", letterSpacing: "-0.5px"
        }}>Alex Rivera</div>

        {/* Call type chip */}
        <div style={{
          marginTop: "10px", display: "flex", alignItems: "center", gap: "6px",
          padding: "5px 13px", borderRadius: "20px",
          background: "rgba(255,255,255,0.07)", border: "1px solid rgba(255,255,255,0.1)"
        }}>
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#00C9E0" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
            <polygon points="23 7 16 12 23 17 23 7"/>
            <rect x="1" y="5" width="15" height="14" rx="2"/>
          </svg>
          <span style={{ color: "rgba(255,255,255,0.65)", fontSize: "13px", fontWeight: 500 }}>
            Incoming video call
          </span>
        </div>

        {/* E2E */}
        <div style={{
          marginTop: "8px", display: "flex", alignItems: "center", gap: "4px",
          color: "rgba(255,255,255,0.28)", fontSize: "11px"
        }}>
          <Lock size={10} />
          <span>End-to-end encrypted</span>
        </div>

        <div style={{ flex: 1 }} />

        {/* Hint labels */}
        <div style={{
          display: "flex", justifyContent: "center", gap: "80px",
          marginBottom: "12px", paddingHorizontal: "0"
        }}>
          <span style={{ color: "rgba(255,255,255,0.4)", fontSize: "12px", width: "80px", textAlign: "center" }}>Decline</span>
          <span style={{ color: "rgba(255,255,255,0.4)", fontSize: "12px", width: "80px", textAlign: "center" }}>Accept</span>
        </div>

        {/* Buttons */}
        <div style={{
          display: "flex", justifyContent: "center", gap: "72px", paddingBottom: "52px"
        }}>
          {/* Decline */}
          <div style={{
            width: "72px", height: "72px", borderRadius: "50%",
            background: "#E53935",
            display: "flex", alignItems: "center", justifyContent: "center",
            boxShadow: "0 8px 24px rgba(229,57,53,0.35)"
          }}>
            <PhoneOff size={28} color="white" strokeWidth={2} />
          </div>
          {/* Accept */}
          <div style={{
            width: "72px", height: "72px", borderRadius: "50%",
            background: "#34A853",
            display: "flex", alignItems: "center", justifyContent: "center",
            boxShadow: "0 8px 24px rgba(52,168,83,0.35)"
          }}>
            <Phone size={28} color="white" strokeWidth={2} />
          </div>
        </div>

        {/* Home indicator */}
        <div style={{
          width: "134px", height: "5px", borderRadius: "3px",
          background: "rgba(255,255,255,0.25)", marginBottom: "8px"
        }} />
      </div>
    </PhoneFrame>
  );
}
