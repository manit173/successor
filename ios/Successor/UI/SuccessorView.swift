import SwiftUI

struct SuccessorView: View {
    @Environment(AppModel.self) private var model
    @State private var target: Int?
    @State private var limitAge = true
    @State private var maxAge = 24
    @State private var minMinutes = 900
    @State private var k = 10
    @State private var projected = true
    @State private var samePosition = false
    @State private var leagues: Set<Int> = []
    @State private var result: SuccessorResult?
    @State private var error: String?
    @State private var running = false
    @State private var picking = false
    @State private var showFilters = false

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
            ScrollView {
                if let e = model.engine {
                    VStack(alignment: .leading, spacing: 16) {
                        targetCard(e)
                        filtersCard(e)
                        Button { run(e) } label: {
                            HStack {
                                if running { ProgressView().tint(.white) }
                                Text(running ? "Searching…" : "Find successors").bold()
                            }
                            .frame(maxWidth: .infinity).padding(.vertical, 6)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(target == nil || running || leagues.isEmpty)
                        if let error { NoteBadge(text: error) }
                        if let r = result { results(e, r) }
                    }
                    .padding()
                }
            }
            .onChange(of: result?.target) {
                if result != nil { withAnimation { proxy.scrollTo("results", anchor: .top) } }
            }
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Find a successor")
            .navigationDestination(for: Int.self) { PlayerView(player: $0) }
            .sheet(isPresented: $picking) {
                PlayerPicker(title: "Who is leaving?") { p in target = p; result = nil; if let e = model.engine { run(e) } }
            }
            .onAppear { syncFromModel() }
            .onChange(of: model.successorTarget) { syncFromModel() }
        }
    }

    private func syncFromModel() {
        guard let e = model.engine else { return }
        if leagues.isEmpty { leagues = Set(e.seasons.map(\.league)) }
        if let t = model.successorTarget {
            model.successorTarget = nil
            target = t
            result = nil
            run(e)
        }
    }

    private func run(_ e: Engine) {
        guard let t = target else { return }
        var q = SuccessorRequest(player: t)
        q.maxAge = limitAge ? maxAge : nil
        q.minMinutes = minMinutes
        q.k = k
        q.useProjection = projected
        q.samePositionOnly = samePosition
        let all = Set(e.seasons.map(\.league))
        q.leagues = leagues == all ? nil : leagues.sorted()
        running = true
        error = nil
        let request = q
        Task {
            let out: Result<SuccessorResult, Error> = await Task.detached { Result { try e.successor(request) } }.value
            running = false
            switch out {
            case .success(let r): result = r
            case .failure(let err): result = nil; error = err.localizedDescription
            }
        }
    }

    // MARK: form

    private func targetCard(_ e: Engine) -> some View {
        Card {
            if let t = target {
                HStack {
                    PlayerRowView(player: t)
                    Spacer()
                    Button("Change") { picking = true }.font(.subheadline)
                }
            } else {
                Button { picking = true } label: {
                    Label("Choose the player who is leaving", systemImage: "person.crop.circle.badge.plus")
                        .frame(maxWidth: .infinity).padding(.vertical, 8)
                }
            }
        }
    }

    private func filtersCard(_ e: Engine) -> some View {
        let present = Set(e.seasons.map(\.league))
        return Card {
            DisclosureGroup(isExpanded: $showFilters) {
                VStack(alignment: .leading, spacing: 12) {
                    Toggle("Max age next season", isOn: $limitAge)
                    if limitAge { Stepper("Up to \(maxAge)", value: $maxAge, in: 17...40) }
                    Stepper("Min minutes per season: \(minMinutes)", value: $minMinutes, in: 0...3000, step: 300)
                    Stepper("Show \(k)", value: $k, in: 5...30, step: 5)
                    Picker("Candidates", selection: $projected) {
                        Text("Projected \(e.label(e.currentSeason + 1))").tag(true)
                        Text("Recent seasons").tag(false)
                    }
                    .pickerStyle(.segmented)
                    Toggle("Same position only", isOn: $samePosition)
                    Text("Off: neighbouring positions are included too (e.g. wingers for an attacking midfielder) and flagged.")
                        .font(.caption).foregroundStyle(.secondary)
                    NavigationLink {
                        LeaguePicker(selection: $leagues, present: present)
                    } label: {
                        HStack {
                            Text("Leagues")
                            Spacer()
                            Text(leagues == present ? "All \(present.count)" : "\(leagues.count) selected").foregroundStyle(.secondary)
                        }
                    }
                }
                .padding(.top, 8)
            } label: {
                Label("Filters", systemImage: "slider.horizontal.3").font(.headline)
            }
        }
    }

    // MARK: results

    @ViewBuilder
    private func results(_ e: Engine, _ r: SuccessorResult) -> some View {
        let tName = e.players[r.target].name
        Card(title: "\(tName) at his peak",
             subtitle: "\(e.positions[r.position].label) · \(r.role.name) · \(r.peak.seasons.map(e.label).joined(separator: ", ")) (role fit \(Int(r.peak.rating.rounded())))") {
            FlowLayout(spacing: 6) {
                ForEach(r.role.weights.map(\.stat).filter { !r.profile[$0].isNaN }, id: \.self) { st in
                    Chip(text: "\(shortLabel(e.stats[st].key, e.stats[st].label)) \(String(format: "%+.1f", r.profile[st]))",
                         tint: r.profile[st] >= 0 ? Palette.pitch : .secondary)
                }
            }
            Text("Robust z-scores against Big-5 \(e.positions[r.position].label.lowercased())s (0 = median, +1 ≈ one standard deviation above).")
                .font(.caption2).foregroundStyle(.secondary)
        }
        .id("results")
        Text("\(r.candidates.count) closest of \(r.pool.formatted()) players · \(r.skipped.formatted()) skipped (too few shared stats)")
            .font(.caption).foregroundStyle(.secondary)
        ForEach(Array(r.candidates.enumerated()), id: \.element.id) { i, c in
            NavigationLink(value: c.player) { CandidateRow(rank: i + 1, c: c, engine: e) }.buttonStyle(.plain)
        }
        if let best = r.candidates.first {
            let axes = r.role.weights.map(\.stat).filter { !r.profile[$0].isNaN && !best.profile[$0].isNaN }
            Card(title: "Best match", subtitle: "\(e.players[best.player].name) · \(Int(best.similarity.rounded()))% similar") {
                if axes.count >= 3 {
                    Radar(axes: axes.map { shortLabel(e.stats[$0].key, e.stats[$0].label) },
                          series: [.init(name: tName, values: axes.map { pct(r.profile[$0]) }, color: .orange),
                                   .init(name: e.players[best.player].name, values: axes.map { pct(best.profile[$0]) },
                                         color: Palette.pitch)])
                        .frame(maxWidth: 340).frame(maxWidth: .infinity)
                    HStack(spacing: 14) {
                        Label(tName + " (peak)", systemImage: "circle.fill").foregroundStyle(.orange)
                        Label(e.players[best.player].name, systemImage: "circle.fill").foregroundStyle(Palette.pitch)
                    }
                    .font(.caption).labelStyle(.titleAndIcon)
                }
            }
        }
        Text("Similarity = 100·exp(−d²/4), d = weighted distance between profiles over the role's stats (each candidate as his \(projected ? "projected \(r.nextSeason)" : "recent seasons")). Top \(r.candidates.count) by quickselect.")
            .font(.caption2).foregroundStyle(.secondary)
    }

    /** A z-score as a 0–100 position on the normal curve, for the radar. */
    private func pct(_ z: Double) -> Double { z.isNaN ? .nan : 50 * (1 + erf(z / 2.0.squareRoot())) }
}

private struct CandidateRow: View {
    let rank: Int
    let c: Candidate
    let engine: Engine

    var body: some View {
        let e = engine
        let p = e.players[c.player]
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                Text("\(rank)").font(.caption.bold().monospacedDigit()).foregroundStyle(.secondary).frame(width: 18)
                Avatar(name: p.name, position: c.position >= 0 ? e.positions[c.position].code : nil, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(p.name).font(.body.weight(.semibold)).lineLimit(1)
                    Text("\(c.ageNextSeason) · \(c.position >= 0 ? e.positions[c.position].code : "–") · \(c.club)")
                        .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    Text(e.leagues[c.league].label).font(.caption2).foregroundStyle(.secondary)
                }
                Spacer()
                Ring(value: c.similarity, size: 46, lineWidth: 5)
            }
            if !c.closest.isEmpty {
                Text("Closest on " + c.closest.map(Engine.phrase).joined(separator: ", ")).font(.caption).foregroundStyle(.secondary)
            }
            if !c.differences.isEmpty {
                Text(c.differences.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
            }
            if let n = c.positionNote { NoteBadge(text: n) }
            if let n = c.leagueNote { NoteBadge(text: n) }
        }
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

struct LeaguePicker: View {
    @Environment(AppModel.self) private var model
    @Binding var selection: Set<Int>
    let present: Set<Int>

    var body: some View {
        if let e = model.engine {
            let ordered = present.sorted()
            List {
                Section {
                    HStack {
                        Button("Big 5") { selection = Set(ordered.filter { e.leagues[$0].big5 }) }
                        Spacer()
                        Button("All") { selection = present }
                        Spacer()
                        Button("None") { selection = [] }
                    }
                    .buttonStyle(.borderless)
                }
                Section(footer: Text("Numbers from leagues outside the Big 5 are converted to Big-5 level by factors measured from players who moved into the Big 5.")) {
                    ForEach(ordered, id: \.self) { l in
                        Button {
                            if selection.contains(l) { selection.remove(l) } else { selection.insert(l) }
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(e.leagues[l].label).foregroundStyle(.primary)
                                    Text(e.leagues[l].big5 ? "\(e.leagues[l].country) · Big 5"
                                         : "\(e.leagues[l].country)\(e.leagues[l].headline.map { " · \($0)" } ?? "")")
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                if selection.contains(l) { Image(systemName: "checkmark").foregroundStyle(.tint) }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Leagues")
        }
    }
}
