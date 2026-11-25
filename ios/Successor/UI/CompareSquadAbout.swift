import SwiftUI

private let seriesColors: [Color] = [Palette.pitch, .orange, Color(red: 0.35, green: 0.45, blue: 0.95)]

// MARK: - Compare

struct CompareView: View {
    @Environment(AppModel.self) private var model
    @State private var picking = false

    var body: some View {
        NavigationStack {
            ScrollView {
                if let e = model.engine {
                    VStack(alignment: .leading, spacing: 16) {
                        Card(title: "Players", subtitle: "Two or three, compared on the stats the first player's role is judged by.") {
                            ForEach(Array(model.compareIds.enumerated()), id: \.element) { i, p in
                                HStack {
                                    Circle().fill(seriesColors[i % 3]).frame(width: 10, height: 10)
                                    PlayerRowView(player: p)
                                    Spacer()
                                    Button { model.compareIds.removeAll { $0 == p } } label: {
                                        Image(systemName: "xmark.circle.fill").foregroundStyle(.tertiary)
                                    }
                                }
                            }
                            if model.compareIds.count < 3 {
                                Button { picking = true } label: { Label("Add a player", systemImage: "plus.circle.fill") }
                            }
                        }
                        if model.compareIds.count >= 2 {
                            switch Result(catching: { try e.compare(model.compareIds) }) {
                            case .success(let r): comparison(e, r)
                            case .failure(let err): NoteBadge(text: err.localizedDescription)
                            }
                        }
                    }
                    .padding()
                }
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Compare")
            .sheet(isPresented: $picking) {
                PlayerPicker(title: "Add to compare") { p in if !model.compareIds.contains(p) { model.compareIds.append(p) } }
            }
        }
    }

    @ViewBuilder
    private func comparison(_ e: Engine, _ r: CompareResult) -> some View {
        let axes = r.role.weights.map(\.stat).filter { st in r.columns.allSatisfy { $0.stats[st] != nil } }
        Card(title: "\(r.role.name) profile", subtitle: "Compared as \(e.positions[r.position].label.lowercased())s · percentiles in each player's latest season measuring the stat") {
            if axes.count >= 3 {
                Radar(axes: axes.map { shortLabel(e.stats[$0].key, e.stats[$0].label) },
                      series: r.columns.enumerated().map { i, c in
                          .init(name: e.players[c.player].name, values: axes.map { c.stats[$0]!.percentile }, color: seriesColors[i % 3])
                      })
                    .frame(maxWidth: 340).frame(maxWidth: .infinity)
            }
            ForEach(Array(r.columns.enumerated()), id: \.element.id) { i, c in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Circle().fill(seriesColors[i % 3]).frame(width: 8, height: 8)
                        Text(e.players[c.player].name).font(.subheadline.weight(.semibold))
                        Text("· \(c.club) · \(c.seasons.map(e.label).joined(separator: " + "))").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    if let n = c.positionNote { NoteBadge(text: n) }
                    if let n = c.leagueNote { NoteBadge(text: n) }
                }
            }
            ForEach(0..<r.columns.count, id: \.self) { a in
                ForEach((a + 1)..<r.columns.count, id: \.self) { b in
                    Text("\(e.players[r.columns[a].player].name) ↔ \(e.players[r.columns[b].player].name): \(r.similarity[a][b].isNaN ? "too few shared stats" : "\(Int(r.similarity[a][b].rounded()))% similar")")
                        .font(.footnote.weight(.medium))
                }
            }
        }
        Card(title: "Stat by stat", subtitle: "Per 90 and percentile; bold stats define the role") {
            ForEach(r.statsShown, id: \.self) { st in
                VStack(alignment: .leading, spacing: 5) {
                    Text(e.stats[st].label).font(r.role.statSet.contains(st) ? .subheadline.bold() : .subheadline)
                    ForEach(Array(r.columns.enumerated()), id: \.element.id) { i, c in
                        HStack(spacing: 8) {
                            PercentBar(value: c.stats[st]?.percentile ?? .nan, tint: seriesColors[i % 3])
                            Text(c.stats[st].map { String(format: "%.2f · %@", $0.per90, ordinal($0.percentile)) } ?? "not measured")
                                .font(.caption.monospacedDigit()).foregroundStyle(.secondary).frame(width: 96, alignment: .trailing)
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Squad planner

struct SquadView: View {
    @Environment(AppModel.self) private var model
    @State private var picking = false
    @State private var limitAge = true
    @State private var maxAge = 27
    @State private var plan: SquadPlan?
    @State private var error: String?
    @State private var running = false
    @State private var seenToken = 0

    var body: some View {
        NavigationStack {
            ScrollView {
                if let e = model.engine {
                    VStack(alignment: .leading, spacing: 16) {
                        Card(title: "Leaving", subtitle: "Several players leave at once: each gets a different replacement, making the weakest replacement as close as possible.") {
                            ForEach(model.squadIds, id: \.self) { p in
                                HStack {
                                    PlayerRowView(player: p)
                                    Spacer()
                                    Button { model.squadIds.removeAll { $0 == p }; plan = nil } label: {
                                        Image(systemName: "xmark.circle.fill").foregroundStyle(.tertiary)
                                    }
                                }
                            }
                            if model.squadIds.count < 8 {
                                Button { picking = true } label: { Label("Add a departing player", systemImage: "plus.circle.fill") }
                            }
                            Divider()
                            Toggle("Max age next season", isOn: $limitAge)
                            if limitAge { Stepper("Up to \(maxAge)", value: $maxAge, in: 17...40) }
                        }
                        Button { run(e) } label: {
                            HStack {
                                if running { ProgressView().tint(.white) }
                                Text(running ? "Planning…" : "Plan replacements").bold()
                            }
                            .frame(maxWidth: .infinity).padding(.vertical, 6)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(model.squadIds.isEmpty || running)
                        if let error { NoteBadge(text: error) }
                        if let plan {
                            planCard(e, title: "Best plan: weakest replacement \(fmt(plan.weakest))", rows: plan.matching)
                            planCard(e, title: "Greedy pick for comparison: weakest \(fmt(plan.greedyWeakest))", rows: plan.greedy)
                            Text("Each role keeps its top \(plan.perRole) candidates (quickselect). Bottleneck matching (max-flow) fills every role with a different player; greedy takes the best pair first and never reconsiders. \(plan.candidates) candidates, \(plan.edges) edges; judged on projected \(plan.nextSeason).")
                                .font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                    .padding()
                }
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Squad planner")
            .navigationDestination(for: Int.self) { PlayerView(player: $0) }
            .onAppear { if model.squadRunToken != seenToken, let e = model.engine { seenToken = model.squadRunToken; run(e) } }
            .onChange(of: model.squadRunToken) { if let e = model.engine { seenToken = model.squadRunToken; run(e) } }
            .sheet(isPresented: $picking) {
                PlayerPicker(title: "Who is leaving?") { p in
                    if !model.squadIds.contains(p) { model.squadIds.append(p) }
                    plan = nil
                }
            }
        }
    }

    private func fmt(_ v: Double) -> String { v.isNaN ? "–" : "\(Int(v.rounded()))% similar" }

    private func run(_ e: Engine) {
        let ids = model.squadIds
        let age = limitAge ? maxAge : nil
        running = true
        error = nil
        Task {
            let out: Result<SquadPlan, Error> = await Task.detached { Result { try e.plan(departures: ids, maxAge: age) } }.value
            running = false
            switch out {
            case .success(let p): plan = p
            case .failure(let err): plan = nil; error = err.localizedDescription
            }
        }
    }

    private func planCard(_ e: Engine, title: String, rows: [Assignment]) -> some View {
        Card(title: title) {
            ForEach(rows) { a in
                VStack(alignment: .leading, spacing: 6) {
                    Text("\(e.players[a.departure].name) · \(e.positions[a.position].code), \(a.role.name)")
                        .font(.caption).foregroundStyle(.secondary)
                    if let c = a.candidate {
                        NavigationLink(value: c) {
                            HStack(spacing: 12) {
                                Image(systemName: "arrow.turn.down.right").foregroundStyle(.tertiary)
                                Avatar(name: e.players[c].name, position: a.candidatePosition.map { e.positions[$0].code }, size: 36)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(e.players[c].name).font(.subheadline.weight(.semibold))
                                    Text("\(a.age) · \(a.candidatePosition.map { e.positions[$0].code } ?? "–") · \(a.club ?? "") · \(a.league.map { e.leagues[$0].label } ?? "")")
                                        .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                }
                                Spacer()
                                Ring(value: a.similarity, size: 40, lineWidth: 4)
                            }
                        }
                        .buttonStyle(.plain)
                        if let n = a.positionNote { NoteBadge(text: n) }
                        if let n = a.leagueNote { NoteBadge(text: n) }
                    } else {
                        Text("No different candidate left for this role").font(.subheadline).foregroundStyle(Palette.warn)
                    }
                }
            }
        }
    }
}

// MARK: - About

struct AboutView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        NavigationStack {
            List {
                if let e = model.engine {
                    Section("Data on this phone") {
                        LabeledContent("Players", value: e.players.count.formatted())
                        LabeledContent("Player-seasons", value: e.seasons.count.formatted())
                        LabeledContent("Seasons", value: "\(e.label(e.seasons.map(\.season).min() ?? 0)) – \(e.label(e.currentSeason))")
                        LabeledContent("Leagues", value: "\(Set(e.seasons.map(\.league)).count)")
                        LabeledContent("Dataset", value: e.isSample ? "Synthetic sample" : "Real (built on the Mac)")
                    }
                    Section {
                        Text("The Java engine on the Mac links the sources, builds the comparison groups, measures league strength and projects every active player's next season. It exports the result to this app, which answers searches, profiles, successor searches, comparisons and squad plans on the phone with no connection. Tests check the phone gives the Java engine's exact answers.")
                            .font(.footnote)
                    } header: { Text("How it works") }
                    Section {
                        ForEach(e.backtest.overall, id: \.method) { m in
                            HStack {
                                Text(methodName(m.method)).font(m.method == e.backtest.chosenMethod ? .subheadline.bold() : .subheadline)
                                Spacer()
                                Text(m.mae.map { String(format: "%.3f", $0) } ?? "–").monospacedDigit()
                                Text(m.vsNaive.map { String(format: "%+.1f%%", $0) } ?? "").font(.caption).foregroundStyle(.secondary).frame(width: 56, alignment: .trailing)
                            }
                        }
                        if let lc = e.backtest.leagueCheck {
                            LabeledContent("Arrivals from other leagues (\(lc.arrivals))",
                                           value: "\(lc.arrivalsMae.map { String(format: "%.3f", $0) } ?? "–") vs \(lc.arrivalsMaeUnadjusted.map { String(format: "%.3f", $0) } ?? "–") unadjusted")
                                .font(.footnote)
                        }
                    } header: {
                        Text("Projection accuracy")
                    } footer: {
                        Text("Error in z units on test seasons \(e.backtest.testSeasons.map(e.label).joined(separator: ", ")), each projected only from earlier seasons; the method was chosen on \(e.backtest.trainSeasons.map(e.label).joined(separator: ", ")).")
                    }
                    Section("Algorithms") {
                        LabeledContent("Randomized quickselect", value: "percentiles, top-k")
                        LabeledContent("Bipartite matching (max-flow)", value: "linking, squad plan")
                        LabeledContent("Trie", value: "player search (Mac)")
                        LabeledContent("Bloom filter", value: "duplicate check (Mac)")
                    }
                    Section {
                        Text("Transfermarkt (CC0), Understat, FBref (Opta / StatsBomb) and Sofascore. The real dataset is built on your Mac and installed only on your own phone; it is never published.")
                            .font(.footnote)
                    } header: { Text("Sources") }
                }
            }
            .navigationTitle("About")
        }
    }

    private func methodName(_ m: String) -> String {
        switch m {
        case "NAIVE": return "Naive (next = last)"
        case "MEAN3": return "Mean of last three"
        case "MARCEL": return "Marcel"
        case "COMPARABLES": return "Comparables"
        default: return "Blend"
        }
    }
}
