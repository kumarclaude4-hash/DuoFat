import { Phone, PhoneOff, Lock } from "lucide-react";

export function IncomingCall() {
  return (
    <div style={{
      width: "100%", height: "100vh", background: "#0D1118",
      display: "flex", flexDirection: "column", alignItems: "center",
      fontFamily: "'Inter', sans-serif", overflow: "hidden", position: "relative"
    }}>
      {/* Status bar */}
      <div style={{
        width: "100%", display: "flex", justifyContent: "space-between",
        alignItems: "center", padding: "14px 24px 8px",
        color: "rgba(255,255,255,0.85)", fontSize: "13px", fontWeight: 600
      }}>
        <span>9:41</span>
        <div style={{ display: "flex", gap: "6px", alignItems: "center" }}>
          <svg width="16" height="12" viewBox="0 0 16 12" fill="white" opacity={0.85}>
            <rect x="0" y="4" width="3" height="8" rx="0.8"/>
            <rect x="4.5" y="2.5" width="3" height="9.5" rx="0.8"/>
            <rect x="9" y="0.5" width="3" height="11.5" rx="0.8"/>
            <rect x="13.5" y="0" width="2.5" height="12" rx="0.8" opacity="0.3"/>
          </svg>
          <svg width="15" height="12" viewBox="0 0 15 12" fill="white" opacity={0.85}>
            <path d="M7.5 2.5C9.8 2.5 11.9 3.4 13.4 4.9L15 3.3C13.1 1.2 10.4 0 7.5 0S1.9 1.2 0 3.3L1.6 4.9C3.1 3.4 5.2 2.5 7.5 2.5Z"/>
            <path d="M7.5 5C9 5 10.4 5.6 11.4 6.6L13 5C11.6 3.6 9.7 2.8 7.5 2.8S3.4 3.6 2 5L3.6 6.6C4.6 5.6 6 5 7.5 5Z"/>
            <circle cx="7.5" cy="9.5" r="2.2"/>
          </svg>
          <svg width="25" height="12" viewBox="0 0 25 12" fill="none" opacity={0.85}>
            <rect x="0.5" y="0.5" width="21" height="11" rx="3.5" stroke="white" strokeOpacity="0.4"/>
            <rect x="2" y="2" width="16" height="8" rx="2" fill="white"/>
            <path d="M23 4.5v3c.8-.4 1.3-1 1.3-1.5S23.8 4.9 23 4.5Z" fill="white" opacity={0.4}/>
          </svg>
        </div>
      </div>

      {/* Incoming call label */}
      <div style={{
        marginTop: "24px", color: "rgba(255,255,255,0.5)", fontSize: "13px",
        letterSpacing: "0.08em", textTransform: "uppercase", fontWeight: 500
      }}>Incoming call</div>

      {/* Avatar */}
      <div style={{ marginTop: "48px", position: "relative" }}>
        {/* Pulsing ring */}
        <div style={{
          position: "absolute", inset: "-16px", borderRadius: "50%",
          background: "radial-gradient(circle, rgba(0,201,224,0.12) 0%, transparent 70%)"
        }} />
        <div style={{
          width: "140px", height: "140px", borderRadius: "50%",
          background: "linear-gradient(145deg, #00C9E0 0%, #0B3A50 100%)",
          display: "flex", alignItems: "center", justifyContent: "center",
          fontSize: "58px", fontWeight: "bold", color: "white",
          boxShadow: "0 0 0 3px rgba(0,201,224,0.25), 0 16px 40px rgba(0,0,0,0.5)"
        }}>A</div>
      </div>

      {/* Name */}
      <div style={{
        marginTop: "28px", fontSize: "30px", fontWeight: 700,
        color: "#FFFFFF", letterSpacing: "-0.5px"
      }}>Alex Rivera</div>

      {/* Call type chip */}
      <div style={{
        marginTop: "12px", display: "flex", alignItems: "center", gap: "6px",
        padding: "6px 14px", borderRadius: "20px",
        background: "rgba(255,255,255,0.08)", border: "1px solid rgba(255,255,255,0.1)"
      }}>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#00C9E0" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
          <polygon points="23 7 16 12 23 17 23 7"/>
          <rect x="1" y="5" width="15" height="14" rx="2" ry="2"/>
        </svg>
        <span style={{ color: "rgba(255,255,255,0.7)", fontSize: "13px", fontWeight: 500 }}>
          Incoming video call
        </span>
      </div>

      {/* E2E label */}
      <div style={{
        marginTop: "10px", display: "flex", alignItems: "center", gap: "5px",
        color: "rgba(255,255,255,0.3)", fontSize: "12px"
      }}>
        <Lock size={11} />
        <span>End-to-end encrypted</span>
      </div>

      {/* Spacer */}
      <div style={{ flex: 1 }} />

      {/* Buttons */}
      <div style={{
        width: "100%", display: "flex", justifyContent: "center",
        gap: "72px", paddingBottom: "60px", alignItems: "center"
      }}>
        {/* Decline */}
        <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: "10px" }}>
          <button style={{
            width: "80px", height: "80px", borderRadius: "50%",
            background: "#E53935", border: "none", cursor: "pointer",
            display: "flex", alignItems: "center", justifyContent: "center",
            boxShadow: "0 8px 24px rgba(229,57,53,0.4)"
          }}>
            <PhoneOff size={32} color="white" strokeWidth={2} />
          </button>
        </div>

        {/* Accept */}
        <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: "10px" }}>
          <button style={{
            width: "80px", height: "80px", borderRadius: "50%",
            background: "#34A853", border: "none", cursor: "pointer",
            display: "flex", alignItems: "center", justifyContent: "center",
            boxShadow: "0 8px 24px rgba(52,168,83,0.4)"
          }}>
            <Phone size={32} color="white" strokeWidth={2} />
          </button>
        </div>
      </div>
    </div>
  );
}
