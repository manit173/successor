import Charts
import SwiftUI

struct PlayerView: View {
    @Environment(AppModel.self) private var model
    let player: Int
    @State private var seasonRow: Int?
    @State private var trendStat: Int?

    var body: some View {
        if let e = model.engine {
            let analysis = e.analyse(player)
            let eligible = analysis.filter { $0.eligible && !$0.roles.isEmpty }
            let selected = eligible.first { $0.row == seasonRow } ?? eligible.last
            let projection = try? e.projectionView(player)
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    header(e, analysis: analysis, latest: eligible.last)
                    actions
                    if let s = selected {
                        roleCard(e, s, choices: eligible)
                        percentiles(e, s)
                    } else {
                        Card { Text("No season with \(Int(e.minMinutes))+ minutes, so there is no role profile.").foregroundStyle(.secondary) }
                    }
                    if let projection {
                        career(e, projection)
                        projectionCard(e, projection)
                    }
                    seasonsCard(e, analysis)
                }
                .padding()
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle(e.players[player].name)
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private func header(_ e: Engine, analysis: [SeasonAnalysis], latest: SeasonAnalysis?) -> some View {
        let p = e.players[player]
        let last = analysis.last
        let posCode = last?.position.map { e.positions[$0].code } ?? (p.position >= 0 ? e.positions[p.position].code : nil)
        return HStack(alignment: .top, spacing: 14) {
            Avatar(name: p.name, position: posCode, size: 68)
            VStack(alignment: .leading, spacing: 8) {
                Text(p.name).font(.title2.bold())
                FlowChips(items: [
                    last?.position.map { (e.positions[$0].label, Palette.position(e.positions[$0].code), true) },
                    latest?.roles.first.map { ($0.name, Palette.position(posCode), false) },
                    last.map { ("\($0.club) · \(e.leagues[$0.league].label)", Color.secondary, false) },
                    e.age(player, atSeason: e.currentSeason) > 0 ? ("\(e.age(player, atSeason: e.currentSeason)) years", .secondary, false) : nil,
                    p.nation.isEmpty ? nil : (p.nation, .secondary, false),
                    money(p.value).map { ($0, Palette.pitch, false) },
                ].compactMap { $0 })
            }
        }
    }

    private var actions: some View {
        HStack(spacing: 10) {
            Button { model.findSuccessor(of: player) } label: {
                Label("Successor", systemImage: "arrow.triangle.swap").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            Button { model.addToCompare(player) } label: {
                Label("Compare", systemImage: "square.split.2x1").frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            Button { model.addToSquad(player) } label: {
                Label("Replace", systemImage: "person.3").frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
        }
        .controlSize(.regular)
        .labelStyle(.titleAndIcon)
        .font(.footnote.weight(.semibold))
    }

    private func roleCard(_ e: Engine, _ s: SeasonAnalysis, choices: [SeasonAnalysis]) -> some View {
        let top = s.roles.first
        let role = top.flatMap { e.role(id: $0.roleId) }
        let lines = Dictionary(uniqueKeysWithValues: s.stats.map { ($0.stat, $0) })
        // the role's stats; when a season measured fewer than six of them, the position's other stats fill in
        var axes = role?.weights.compactMap { lines[$0.stat] } ?? []
        if axes.count < 6, let pos = s.position {
            for (st, _) in e.positions[pos].stats.sorted(by: { $0.weight > $1.weight }) where axes.count < 8 {
                if let l = lines[st], !axes.contains(where: { $0.stat == st }) { axes.append(l) }
            }
        }
        return Card {
            HStack {
                Text("Role and percentiles").font(.headline)
                Spacer()
                Menu {
                    ForEach(choices.reversed()) { c in
                        Button("\(c.label) · \(c.club)") { seasonRow = c.row }
                    }
                } label: {
                    Label("\(s.label) · \(s.club)", systemImage: "chevron.up.chevron.down").font(.caption.weight(.semibold))
                }
            }
            HStack(spacing: 14) {
                Ring(value: top?.fit ?? .nan, size: 64, lineWidth: 6, caption: "fit")
                VStack(alignment: .leading, spacing: 3) {
                    Text(top?.name ?? "–").font(.title3.bold())
                    Text(role?.description ?? "").font(.footnote).foregroundStyle(.secondary)
                    Text(s.roles.dropFirst().prefix(2).map { "\($0.name) \($0.fit.isNaN ? "–" : "\(Int($0.fit.rounded()))")" }
                        .joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
                }
            }
            if axes.count >= 3 {
                Radar(axes: axes.map { shortLabel($0.key, $0.label) },
                      series: [.init(name: e.players[player].name, values: axes.map(\.percentile),
                                     color: Palette.position(s.position.map { e.positions[$0].code }))])
                    .frame(maxWidth: 340).frame(maxWidth: .infinity)
            }
            if let note = e.leagueNote(s.league) { NoteBadge(text: note) }
            Text("Fit 0–100 = mean percentile on the role's stats against Big-5 \(s.position.map { e.positions[$0].label.lowercased() } ?? "player")s with \(Int(e.minMinutes))+ minutes in \(s.label).")
                .font(.caption2).foregroundStyle(.secondary)
        }
    }

    private func percentiles(_ e: Engine, _ s: SeasonAnalysis) -> some View {
        Card(title: "Per 90 minutes", subtitle: "Percentile among Big-5 players at his position, \(s.label)") {
            ForEach(s.stats.sorted { $0.percentile > $1.percentile }) { l in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(l.label).font(.subheadline)
                        Spacer()
                        Text(String(format: "%.2f", l.per90)).font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
                        Text(ordinal(l.percentile)).font(.subheadline.weight(.semibold).monospacedDigit()).frame(width: 44, alignment: .trailing)
                    }
                    PercentBar(value: l.percentile)
                }
            }
        }
    }

    private func career(_ e: Engine, _ pv: ProjectionView) -> some View {
        let options = pv.stats
        let chosen = options.first { $0.stat == trendStat } ?? options.first
        let points: [(String, Int, Double)] = chosen.map { st in
            e.seasonsOf(player).compactMap { s -> (String, Int, Double)? in
                guard e.minutes(s) >= 450 else { return nil }
                let v = e.per90(s, st.stat)
                return v.isNaN ? nil : (e.label(e.seasons[s].season), e.seasons[s].season, v)
            }
        } ?? []
        return Card {
            HStack {
                Text("Career").font(.headline)
                Spacer()
                Menu {
                    ForEach(options) { o in Button(o.label) { trendStat = o.stat } }
                } label: {
                    Label(chosen?.label ?? "", systemImage: "chevron.up.chevron.down").font(.caption.weight(.semibold))
                }
            }
            if let chosen {
                Chart {
                    ForEach(points, id: \.1) { p in
                        LineMark(x: .value("Season", p.0), y: .value("Per 90", p.2)).interpolationMethod(.monotone)
                        PointMark(x: .value("Season", p.0), y: .value("Per 90", p.2))
                    }
                    if !chosen.low.isNaN {
                        RuleMark(x: .value("Season", pv.season), yStart: .value("Low", chosen.low), yEnd: .value("High", chosen.high))
                            .lineStyle(StrokeStyle(lineWidth: 10, lineCap: .round)).foregroundStyle(Palette.pitch.opacity(0.2))
                    }
                    PointMark(x: .value("Season", pv.season), y: .value("Per 90", chosen.per90))
                        .symbol(.circle).symbolSize(80).foregroundStyle(Palette.pitch)
                        .annotation(position: .top) { Text("proj.").font(.caption2).foregroundStyle(Palette.pitch) }
                }
                .frame(height: 190)
                .chartXAxis { AxisMarks { _ in AxisValueLabel().font(.system(size: 8)) } }
                Text("Per 90, seasons with 450+ minutes. Green: projected \(pv.season) with its 80% range\(pv.stats.isEmpty ? "" : " (in Big-5 terms)").")
                    .font(.caption2).foregroundStyle(.secondary)
            }
        }
    }

    private func projectionCard(_ e: Engine, _ pv: ProjectionView) -> some View {
        Card(title: "Next season · \(pv.season)",
             subtitle: "As a \(e.positions[pv.position].label.lowercased()), age \(pv.age). Marcel: last three seasons weighted 5/4/3, regressed toward the median, plus aging; range from \(pv.comparables) similar players.") {
            ForEach(pv.stats) { s in
                HStack {
                    Text(s.label).font(.subheadline).lineLimit(1)
                    Spacer()
                    Text(s.lastPer90.isNaN ? "–" : String(format: "%.2f", s.lastPer90))
                        .font(.caption.monospacedDigit()).foregroundStyle(.secondary).frame(width: 44, alignment: .trailing)
                    Image(systemName: "arrow.right").font(.caption2).foregroundStyle(.tertiary)
                    VStack(alignment: .trailing, spacing: 0) {
                        Text(String(format: "%.2f", s.per90)).font(.subheadline.weight(.semibold).monospacedDigit())
                        if !s.low.isNaN {
                            Text(String(format: "%.2f–%.2f", s.low, s.high)).font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                        }
                    }
                    .frame(width: 84, alignment: .trailing)
                }
            }
        }
    }

    private func seasonsCard(_ e: Engine, _ analysis: [SeasonAnalysis]) -> some View {
        Card(title: "Seasons") {
            ForEach(analysis.reversed()) { s in
                HStack {
                    Text(s.label).font(.subheadline.monospacedDigit()).frame(width: 64, alignment: .leading)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(s.club).font(.subheadline).lineLimit(1)
                        Text(e.leagues[s.league].label).font(.caption2).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if let p = s.position { Chip(text: e.positions[p].code, tint: Palette.position(e.positions[p].code)) }
                    Text("\(s.minutes)′").font(.caption.monospacedDigit()).foregroundStyle(.secondary).frame(width: 52, alignment: .trailing)
                }
            }
        }
    }
}

/** Chips that wrap onto new lines. */
struct FlowChips: View {
    let items: [(String, Color, Bool)]
    var body: some View {
        FlowLayout(spacing: 6) {
            ForEach(Array(items.enumerated()), id: \.offset) { _, x in Chip(text: x.0, tint: x.1, filled: x.2) }
        }
    }
}

struct FlowLayout: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 320
        var x: CGFloat = 0, y: CGFloat = 0, row: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x + s.width > width && x > 0 { x = 0; y += row + spacing; row = 0 }
            x += s.width + spacing
            row = max(row, s.height)
        }
        return CGSize(width: width, height: y + row)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, row: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x + s.width > bounds.maxX && x > bounds.minX { x = bounds.minX; y += row + spacing; row = 0 }
            v.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(s))
            x += s.width + spacing
            row = max(row, s.height)
        }
    }
}
