import SwiftUI

// Shared look: position colours, avatars, chips, cards, the similarity ring and the radar chart.

enum Palette {
    static let pitch = Color(red: 0.09, green: 0.55, blue: 0.31)
    static let pitchDeep = Color(red: 0.04, green: 0.36, blue: 0.21)
    static let warn = Color(red: 0.93, green: 0.56, blue: 0.0)

    static func position(_ code: String?) -> Color {
        switch code {
        case "GK": return Color(red: 0.95, green: 0.70, blue: 0.10)
        case "CB", "FB": return Color(red: 0.20, green: 0.48, blue: 0.95)
        case "DM", "CM": return Color(red: 0.12, green: 0.65, blue: 0.45)
        case "AM", "W": return Color(red: 0.60, green: 0.35, blue: 0.95)
        case "ST": return Color(red: 0.92, green: 0.30, blue: 0.30)
        default: return .gray
        }
    }

    /** A colour for a similarity or percentile from 0 to 100. */
    static func score(_ v: Double) -> Color {
        if v.isNaN { return .gray }
        if v >= 80 { return Color(red: 0.12, green: 0.70, blue: 0.40) }
        if v >= 60 { return Color(red: 0.40, green: 0.72, blue: 0.20) }
        if v >= 40 { return Color(red: 0.95, green: 0.65, blue: 0.10) }
        return Color(red: 0.90, green: 0.35, blue: 0.30)
    }
}

struct Avatar: View {
    let name: String
    let position: String?
    var size: CGFloat = 44

    var body: some View {
        let initials = name.split(separator: " ").prefix(2).compactMap(\.first).map(String.init).joined()
        let c = Palette.position(position)
        Text(initials.uppercased())
            .font(.system(size: size * 0.38, weight: .bold, design: .rounded))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(LinearGradient(colors: [c.opacity(0.85), c], startPoint: .topLeading, endPoint: .bottomTrailing),
                        in: Circle())
            .shadow(color: c.opacity(0.35), radius: size * 0.12, y: size * 0.06)
    }
}

struct Chip: View {
    let text: String
    var tint: Color = .secondary
    var filled = false

    var body: some View {
        Text(text)
            .font(.caption.weight(.medium))
            .lineLimit(1)
            .padding(.horizontal, 9).padding(.vertical, 4)
            .foregroundStyle(filled ? .white : tint)
            .background(filled ? AnyShapeStyle(tint) : AnyShapeStyle(tint.opacity(0.13)), in: Capsule())
    }
}

/** Amber note for "a different position" or "outside the Big 5". */
struct NoteBadge: View {
    let text: String
    var body: some View {
        Label(text, systemImage: "exclamationmark.triangle.fill")
            .font(.caption2)
            .foregroundStyle(Palette.warn)
            .padding(.horizontal, 8).padding(.vertical, 5)
            .background(Palette.warn.opacity(0.12), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            .fixedSize(horizontal: false, vertical: true)
    }
}

struct Card<Content: View>: View {
    var title: String? = nil
    var subtitle: String? = nil
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let title {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.headline)
                    if let subtitle { Text(subtitle).font(.footnote).foregroundStyle(.secondary) }
                }
            }
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

struct Ring: View {
    let value: Double // 0–100
    var size: CGFloat = 48
    var lineWidth: CGFloat = 5
    var caption: String? = nil

    var body: some View {
        let c = Palette.score(value)
        ZStack {
            Circle().stroke(c.opacity(0.18), lineWidth: lineWidth)
            Circle().trim(from: 0, to: value.isNaN ? 0 : value / 100)
                .stroke(c, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(-90))
            VStack(spacing: 0) {
                Text(value.isNaN ? "–" : "\(Int(value.rounded()))")
                    .font(.system(size: size * 0.3, weight: .bold, design: .rounded))
                if let caption { Text(caption).font(.system(size: size * 0.14)).foregroundStyle(.secondary) }
            }
        }
        .frame(width: size, height: size)
    }
}

/** A percentile bar, 0–100, coloured by level. */
struct PercentBar: View {
    let value: Double
    var tint: Color? = nil
    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.secondary.opacity(0.15))
                Capsule().fill(tint ?? Palette.score(value))
                    .frame(width: value.isNaN ? 0 : max(4, g.size.width * value / 100))
            }
        }
        .frame(height: 7)
    }
}

/** Radar of 0–100 values: one or two series over the same axes. */
struct Radar: View {
    struct Series { let name: String; let values: [Double]; let color: Color }
    let axes: [String]
    let series: [Series]

    var body: some View {
        Canvas { ctx, size in
            let n = axes.count
            guard n >= 3 else { return }
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let radius = min(size.width, size.height) / 2 - 64
            func point(_ i: Int, _ r: Double) -> CGPoint {
                let a = -Double.pi / 2 + 2 * Double.pi * Double(i) / Double(n)
                return CGPoint(x: center.x + cos(a) * radius * r, y: center.y + sin(a) * radius * r)
            }
            for ring in [0.25, 0.5, 0.75, 1.0] {
                var p = Path()
                for i in 0..<n { i == 0 ? p.move(to: point(i, ring)) : p.addLine(to: point(i, ring)) }
                p.closeSubpath()
                ctx.stroke(p, with: .color(.secondary.opacity(ring == 1 ? 0.35 : 0.18)), lineWidth: 1)
            }
            for i in 0..<n {
                var p = Path(); p.move(to: center); p.addLine(to: point(i, 1))
                ctx.stroke(p, with: .color(.secondary.opacity(0.15)), lineWidth: 1)
            }
            for s in series {
                var p = Path()
                for i in 0..<n {
                    let v = s.values[i].isNaN ? 0 : s.values[i] / 100
                    i == 0 ? p.move(to: point(i, v)) : p.addLine(to: point(i, v))
                }
                p.closeSubpath()
                ctx.fill(p, with: .color(s.color.opacity(series.count > 1 ? 0.08 : 0.22)))
                ctx.stroke(p, with: .color(s.color), style: StrokeStyle(lineWidth: 2, lineJoin: .round))
                for i in 0..<n where !s.values[i].isNaN {
                    let pt = point(i, s.values[i] / 100)
                    ctx.fill(Path(ellipseIn: CGRect(x: pt.x - 3, y: pt.y - 3, width: 6, height: 6)), with: .color(s.color))
                }
            }
            for i in 0..<n {
                // labels just outside the rim, anchored away from the centre so they never run into the chart
                let a = -Double.pi / 2 + 2 * Double.pi * Double(i) / Double(n)
                let pt = point(i, 1.07)
                let anchor: UnitPoint = cos(a) > 0.3 ? (sin(a) > 0.5 ? .topLeading : sin(a) < -0.5 ? .bottomLeading : .leading)
                    : cos(a) < -0.3 ? (sin(a) > 0.5 ? .topTrailing : sin(a) < -0.5 ? .bottomTrailing : .trailing)
                    : (sin(a) < 0 ? .bottom : .top)
                let text = Text(axes[i]).font(.system(size: 10, weight: .medium)).foregroundStyle(.secondary)
                ctx.draw(text, at: pt, anchor: anchor)
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityLabel("Radar chart of " + axes.joined(separator: ", "))
    }
}

/** Short axis names for the radar. */
func shortLabel(_ key: String, _ label: String) -> String {
    let map = ["np_goals": "NP goals", "xa": "xA", "npxg": "npxG", "xg": "xG", "key_passes": "Key passes",
               "xg_chain": "xGChain", "xg_buildup": "xGBuildup", "sca": "SCA", "gca": "GCA",
               "prog_passes": "Prog passes", "prog_carries": "Prog carries", "prog_receptions": "Prog received",
               "passes_final_third": "Final third", "passes_into_box": "Into box", "crosses_into_box": "Crosses in box",
               "carries_into_box": "Carries in box", "passes_completed": "Passes", "passes_attempted": "Passes att",
               "touches_att_box": "Box touches", "take_ons": "Take-ons", "tackles_won": "Tackles won",
               "interceptions": "Interceptions", "aerials_won": "Aerials", "recoveries": "Recoveries",
               "goals_against": "Conceded", "shots_on_target_against": "SoT faced", "psxg": "PSxG"]
    return map[key] ?? label
}

func money(_ eur: Double) -> String? {
    if eur <= 0 { return nil }
    if eur >= 1_000_000 { return String(format: "€%.1fm", eur / 1_000_000) }
    return String(format: "€%.0fk", eur / 1_000)
}

func ordinal(_ v: Double) -> String {
    if v.isNaN { return "–" }
    let n = Int(v.rounded())
    let suffix = (11...13).contains(n % 100) ? "th" : ["th", "st", "nd", "rd", "th", "th", "th", "th", "th", "th"][n % 10]
    return "\(n)\(suffix)"
}

/** A player row: avatar, name, position · club · league. */
struct PlayerRowView: View {
    @Environment(AppModel.self) private var model
    let player: Int
    var detail: String? = nil

    var body: some View {
        if let e = model.engine {
            let p = e.players[player]
            let last = e.seasonRange[player].last
            let pos = last.map { e.positionOf($0) } ?? p.position
            let code = pos >= 0 ? e.positions[pos].code : nil
            HStack(spacing: 12) {
                Avatar(name: p.name, position: code, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(p.name).font(.body.weight(.semibold)).lineLimit(1)
                    Text(detail ?? [code, last.map { e.clubs[e.seasons[$0].club] }, last.map { e.label(e.seasons[$0].season) }]
                        .compactMap { $0 }.joined(separator: " · "))
                        .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
        }
    }
}

/** Pick a player by name: a search sheet used by Successor, Compare and Squad. */
struct PlayerPicker: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let title: String
    let onPick: (Int) -> Void
    @State private var query = ""

    var body: some View {
        NavigationStack {
            List {
                if let e = model.engine {
                    ForEach(e.search(query, limit: 30)) { hit in
                        Button { onPick(hit.player); dismiss() } label: { PlayerRowView(player: hit.player) }
                            .buttonStyle(.plain)
                    }
                }
            }
            .overlay {
                if query.trimmingCharacters(in: .whitespaces).isEmpty {
                    ContentUnavailableView("Search players", systemImage: "magnifyingglass",
                                           description: Text("Type part of a name: “cherki”, “de bruyne”, “Ødegaard”."))
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Player name")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
    }
}
