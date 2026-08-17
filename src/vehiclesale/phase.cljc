(ns vehiclesale.phase
  "Phase 0→3 staged rollout — start narrow (read-only), widen as trust
  grows. Where the VehicleSaleGovernor answers 'is this allowed?', the
  phase answers 'how much autonomy does the actor have *yet*?'. It can
  only ever make the actor MORE conservative than the governor.

    Phase 0  read-only        — `:disclosure/query` only (governor-gated).
    Phase 1  assisted-listing — `:vehicle/list` allowed, every listing
                                needs human approval.
    Phase 2  + sale-confirm   — adds `:sale/confirm` and `:dispute/request`
                                (still approval-only).
    Phase 3  supervised auto  — governor-clean, high-confidence
                                `:vehicle/list`/`:sale/confirm` may
                                auto-commit. Money-adjacent ops
                                (`:escrow/capture`, `:escrow/propose-release`,
                                `:payout/bind`, `:x402/unlock`) are in
                                `:writes` but never in `:auto`.
                                `:export/certify` / `:import/permit` are
                                likewise never auto (filings-adjacent).
                                `:border/quote` may auto-commit when
                                governor-clean.

  `:dispute/request` and money-adjacent ops are NEVER members of any
  phase's `:auto` set. The governor also always-escalates the money ops
  and the export/import certs.")

(def read-ops  #{:disclosure/query})
(def write-ops #{:vehicle/list :sale/confirm :dispute/request :inquiry/submit
                 :inquiry/reply :scan/record :escrow/open :escrow/capture
                 :escrow/propose-release :custody/transfer :custody/handover
                 :payout/bind :x402/unlock :border/quote :export/certify
                 :import/permit})

(def phases
  {0 {:label "read-only"          :writes #{}
                                   :auto #{}}
   1 {:label "assisted-listing"   :writes #{:vehicle/list :inquiry/submit :inquiry/reply
                                            :scan/record :border/quote}
                                   :auto #{}}
   2 {:label "assisted-sale"      :writes #{:vehicle/list :sale/confirm :dispute/request
                                            :inquiry/submit :inquiry/reply :scan/record
                                            :escrow/open :custody/transfer :custody/handover
                                            :border/quote :export/certify}
                                   :auto #{}}
   3 {:label "supervised-auto"    :writes write-ops
                                   :auto #{:vehicle/list :sale/confirm :inquiry/submit
                                           :inquiry/reply :scan/record :escrow/open
                                           :custody/transfer :custody/handover
                                           :border/quote}}})

(def default-phase
  "The phase used when `context` carries no :phase at all, AND the fallback
  `gate` itself uses for an unrecognized phase number. This is directly
  reachable by any ordinary caller that simply omits :phase, so it must be
  the MOST CONSERVATIVE phase, never the most permissive (the same
  fail-open bug already found and fixed this session across
  `cloud-itonami-isic-6311`/`-7820` and the shared `talent.phase`
  template — never shipped here in the first place)."
  1)

(defn gate
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)      {:disposition :hold :reason nil}
      (contains? read-ops op)             {:disposition governor-disposition :reason nil}
      (not (contains? writes op))         {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))     {:disposition :escalate :reason :phase-approval}
      :else                               {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
