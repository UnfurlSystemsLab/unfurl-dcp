# LoRA Experiment for Intelligent Components DCP — Refusal Reasoning

**Document status:** v0.2, revised
**Supersedes:** archive/intelligent_components_dcp_lora_plan.md (v0.1, historical)
**Position in the portfolio:** This is the empirical test of whether the intelligent components thesis is feasible with small models. The DCP draft spec, the HLDs, and the entire intelligent components piece of the Unfurl Systems portfolio depend on the outcome of this experiment.

---

## Decision log (v1 → v2)

- **Dataset size:** narrowed from a broad **~500-example multi-category** training set in v1 to a focused **150–200-example refusal dataset** plus a held-out **50-example evaluation set** in v2.
- **Objective narrowing:** reduced scope from multi-behavior DCP training (planning, dependencies, conflicts, negotiation, grounding, and code artifacts) to a single falsifiable target: **refusal and boundary reasoning**.
- **Success criteria:** replaced implicit success framing with explicit pre-committed thresholds (refusal precision/recall, acceptance precision, redirection accuracy, hallucination rate) and a baseline-comparison gate.

---

## 1. What this document is

This is an **experimental design**, not a production training plan. The goal is not to ship a fine-tuned model. The goal is to find out whether a small open-weight model can be made to reliably perform a single, well-defined behavior central to the intelligent components thesis.

Treat the LoRA produced by this experiment as instrumentation. Its purpose is to generate evidence. Whether it ever runs in production is a downstream question.

---

## 2. The question being tested

> Can a 7B-parameter open-weight model, fine-tuned with QLoRA on a small dataset of Domain Claim Protocol reasoning examples, learn to reliably perform refusal and boundary reasoning at a quality acceptable for production use in intelligent components?

This is a single, specific, falsifiable question. The answer is yes, no, or qualified yes (with characterized limitations). All three are acceptable outcomes; the point is to find out.

"Acceptable for production use" is defined concretely in §6 (success criteria).

---

## 3. Why refusal reasoning is the right first target

Of the many behaviors intelligent components need (integration planning, conflict detection, dependency analysis, negotiation, code generation, grounding), refusal/boundary reasoning is the right first experimental target for several reasons:

**1. It is the most distinctive behavior of DCP.** Other behaviors (planning, code generation) have well-studied analogues in existing LLM work. Refusing concerns explicitly because they fall outside a declared domain is a behavior that current systems mostly don't do. Validating that small models can be taught to do it is genuinely informative about the protocol's feasibility.

**2. It is the most legible to evaluate.** Given a domain claim and a request, the model either refuses the right things or doesn't. Pass/fail criteria can be defined precisely. This contrasts with, say, evaluating "negotiation quality," which is subjective.

**3. It is the behavior small models are most likely to fail at.** LLMs are trained to be helpful. Refusing is unnatural to them. If a fine-tuned 7B model can be made to refuse reliably, that's strong evidence that the protocol's other behaviors are within reach. If it can't, we've learned that the protocol's design assumes more capability than small models offer.

**4. It is foundational to everything else.** If a component cannot reliably refuse out-of-domain concerns, no other intelligent component behavior is trustworthy. A component that confidently answers questions it shouldn't is dangerous regardless of how good its other capabilities are.

**5. It is achievable with a small dataset.** Refusal can be taught with focused examples. Other behaviors (multi-component composition, integration plans) require more variety to generalize. A 150-200 example dataset on refusal alone is more likely to produce a clean signal than the same size dataset spread across eight categories.

---

## 4. Pre-experiment baseline

**Before any fine-tuning, establish a baseline.** This is the most important step in the experiment and it should not be skipped.

Test base Qwen 2.5 Coder 7B Instruct (and, for comparison, base Qwen 2.5 7B Instruct without the Coder specialization) with:

- A well-crafted system prompt explaining DCP and the refusal discipline
- The DCP v0.1 draft specification included in the prompt or available via retrieval
- The Intelligent Keycloak domain claim as context
- The 50-example evaluation set (see §7)

Score the base model's outputs against the evaluation criteria. This gives a baseline number.

**The LoRA must beat this baseline meaningfully to be considered successful.** If the base model already gets 85% of evaluation items correct with good prompting, a LoRA that gets 90% might not justify the effort. If the base model gets 45%, a LoRA that gets 80% is a clear win.

A surprising finding to watch for: the base model may already be quite good with the right prompt. If it is, the experiment's answer is "fine-tuning isn't needed for this behavior" — which is itself valuable information. The intelligent components thesis becomes easier, not harder.

**Estimated effort for baseline:** 3-5 days. Build the evaluation set, write the prompt, run base models, score outputs. Document findings.

---

## 5. Recommended base model

Test two candidates for the baseline before committing to one for fine-tuning:

```text
Qwen/Qwen2.5-7B-Instruct          (general-purpose)
Qwen/Qwen2.5-Coder-7B-Instruct    (code-specialized)
```

The Coder variant has stronger handling of structured artifacts (YAML, JSON, schemas), which DCP documents are. The general-purpose variant may have stronger handling of natural-language reasoning and conversational nuance, which the negotiation surface needs.

Run both on the baseline. Pick the stronger one for fine-tuning. If they're roughly equivalent, prefer the non-Coder variant for the first LoRA — its strengths are more aligned with the refusal reasoning task, which is more about understanding intent than generating code.

**Do not start with the 14B or 32B variants.** The experiment's value is partly in knowing what 7B can do. Scaling up after a 7B failure is a meaningful step; starting at 14B muddies the result.

---

## 6. Success criteria

Defined upfront, before training. These are the criteria the LoRA must meet on the evaluation set (§7) to be considered successful:

**Primary criteria (all required):**

1. **Refusal precision ≥ 90%.** When the input describes a request that should be refused according to the domain claim, the model refuses correctly in at least 90% of evaluation cases.

2. **Refusal recall ≥ 85%.** Of all requests in the evaluation set that should be refused, at least 85% are caught. This catches the failure mode where the model refuses some things correctly but lets others slip through.

3. **Acceptance precision ≥ 90%.** When the input describes a request that the domain claim *does* cover, the model accepts it in at least 90% of cases. This catches the opposite failure mode — over-refusal, where the model becomes paranoid and refuses things it shouldn't.

4. **Redirection accuracy ≥ 70%.** When refusing, the model names a plausible owner for the refused concern (e.g., refuses authorization → suggests a policy engine) in at least 70% of refusal cases.

5. **Hallucination rate ≤ 5%.** The model does not invent ownership claims, dependencies, or capabilities that aren't in the provided claim, in at least 95% of cases.

**Secondary criteria (informational, not required for success):**

- Response coherence (qualitative review)
- Tone appropriateness for a technical audience
- Performance vs. base model baseline

**Failure thresholds:**

- If primary criteria 1 or 2 fall below 75%, the LoRA is considered a clear failure for this task.
- If primary criterion 5 (hallucination) is above 10%, the LoRA is considered unsafe to use regardless of other metrics — silent fabrication of ownership is the most dangerous failure mode.

---

## 7. Evaluation dataset

Build a structured evaluation set of **50 examples** before training begins. The eval set is held out from training and never seen by the LoRA during fine-tuning.

Each evaluation example has:

```json
{
  "id": "eval-001",
  "category": "refusal | acceptance | edge_case",
  "input": "...",
  "expected_disposition": "refuse | accept | partial_accept",
  "expected_redirection": "...",  // if refusal, what should be named as owner
  "rationale": "..."  // human-readable reason for expected output
}
```

Distribution:

- **20 clear refusal cases**: requests obviously outside the claim's domain (e.g., asking Intelligent Keycloak to decide invoice approvals)
- **20 clear acceptance cases**: requests obviously within the claim's domain (e.g., asking Intelligent Keycloak about realm configuration)
- **10 edge cases**: deliberately ambiguous requests where reasonable judgment is required (e.g., questions that straddle authentication and session-management boundaries)

The edge cases are diagnostic, not pass/fail. They reveal *how* the model handles ambiguity, which is informative even when no single answer is "correct."

**Scoring is automated where possible, human-reviewed where not.** Refusal vs. acceptance can be detected by keyword/pattern matching with high reliability. Redirection accuracy requires brief human review. Plan for ~2-3 hours of human review per evaluation pass.

---

## 8. Training dataset

Build a training set of **150-200 examples** focused exclusively on refusal reasoning.

Distribution:

- **80 refusal examples**: varied requests that should be refused per the Intelligent Keycloak claim, with clear refusal outputs that explain why and redirect appropriately
- **60 acceptance examples**: varied requests that should be accepted, with appropriate handling. Acceptance examples prevent over-refusal.
- **30 edge cases**: ambiguous requests with thoughtful handling. These teach the model to express uncertainty rather than guess.
- **10 hallucination-prevention examples**: requests asking about properties not in the claim, with outputs that say "the claim doesn't cover this" rather than confabulating.

**Quality over quantity.** Each example should be deliberate, well-written, and exercise a specific reasoning pattern. 150 careful examples are likely to outperform 500 mechanical ones.

**The training and evaluation sets are built from the same human author (Vinay) but never overlap.** Build the eval set first; build the training set second; ensure no leakage. The temptation to "test the model on cases like the training examples" is exactly the trap to avoid.

**Estimated effort to build:** 2-3 weeks of focused authoring. This is real writing work — each example is short but requires careful thought. Plan for it.

---

## 9. The Intelligent Keycloak claim as the single training basis

The first LoRA uses the Intelligent Keycloak DCP claim (from `keycloak-domain-claim-example.md`) as the *only* claim represented in training and evaluation.

This is deliberate. Limiting to one claim means:

- The dataset can be authored with deep understanding of the specific domain
- Edge cases can be identified precisely
- Evaluation cases can be checked against a single source of truth
- Whether the LoRA generalizes to other claims becomes a *follow-up experiment*, not a confounding factor in this one

**The risk:** the LoRA learns Keycloak-specific refusal patterns rather than DCP-general refusal patterns. The follow-up experiment (testing on a second claim, probably Intelligent Provider Registry once that work is done) will determine whether the LoRA generalizes. If it doesn't, the next step is multi-claim training. If it does, we've validated that small models can learn the DCP reasoning pattern from a single example.

The original plan called for multi-component training in the first pass. This was overambitious. One claim done well is more informative than many claims done thinly.

---

## 10. Training configuration

Use QLoRA via LLaMA-Factory. The earlier plan's training command is mostly correct; here are the adjusted parameters for the narrowed scope:

```bash
llamafactory-cli train \
  --stage sft \
  --do_train true \
  --model_name_or_path Qwen/Qwen2.5-7B-Instruct \   # or Coder variant per baseline
  --dataset intelligent_components_dcp_refusal \
  --template qwen \
  --finetuning_type lora \
  --quantization_bit 4 \
  --lora_target all \
  --output_dir saves/qwen2_5_7b/lora/dcp_refusal_v1 \
  --overwrite_cache true \
  --overwrite_output_dir true \
  --cutoff_len 4096 \
  --per_device_train_batch_size 1 \
  --gradient_accumulation_steps 8 \
  --learning_rate 2e-4 \
  --num_train_epochs 3 \
  --lr_scheduler_type cosine \
  --warmup_ratio 0.1 \
  --logging_steps 5 \
  --save_steps 50 \
  --fp16 true
```

Notes:

- `save_steps 50` (vs. 100 in v1) — more checkpoints, since the training set is smaller; we want to inspect intermediate states
- Keep epochs at 3 initially; if loss plateaus early, reduce to 2
- The output is the LoRA adapter; it should not be merged into the base model until §11 evaluation passes

---

## 11. Evaluation procedure

After training completes:

1. **Load the adapter on top of the base model.** Do not merge yet.
2. **Run all 50 evaluation examples through the adapted model.** Capture outputs.
3. **Score each output against the expected disposition.**
   - Refusal vs. acceptance: automated keyword/pattern check
   - Redirection accuracy: human review (~2 hours)
   - Hallucination check: human review of all responses for invented claims
4. **Compute all five primary metrics.** Check against success criteria in §6.
5. **Compare against the baseline from §4.** Did the LoRA meaningfully improve over the base model?

**Decision:**

- **Clear success** (all primary criteria met, meaningful improvement over baseline) → expand the experiment per §13
- **Qualified success** (most criteria met, some gaps) → iterate on the LoRA: identify which categories fail most often, augment training data, retrain
- **Failure** (criteria significantly missed) → execute the failure response plan in §12

---

## 12. Failure response plan

If the LoRA fails to meet success criteria, the next steps are predetermined to prevent reactive scope expansion. In order:

**1. Diagnose the failure mode.** Which criteria failed? Refusal precision (over-permissive) and refusal recall (under-permissive) suggest different fixes. Hallucination failures are more concerning than acceptance precision failures.

**2. Targeted dataset expansion.** If failure is in a specific category (e.g., edge cases handled poorly, certain types of redirection missed), add 30-50 examples specifically targeting that category. Retrain. Re-evaluate.

**3. Try the 14B variant.** If targeted expansion doesn't help, try Qwen 2.5 14B Instruct. The capability gap may simply require more parameters. The same training set should produce stronger results at higher scale.

**4. Reconsider the protocol.** If both 7B and 14B variants fail, the issue may be the protocol itself — DCP as currently shaped may be too subtle for small models to reason about reliably. Revise the protocol to be more structured (more explicit ownership flags, more rigid refusal lists, less reliance on natural-language boundary statements) and retest.

**5. Accept the negative result.** If after all of the above the model still cannot perform refusal reasoning reliably, conclude that the intelligent components thesis as currently scoped requires larger models than 14B, or requires a fundamentally different protocol design. This is a real finding. It does not invalidate Unfurl Systems' portfolio — the orchestrator, the publication, and the principle of domain ownership all stand regardless — but it means intelligent components with embedded small models are not currently feasible. The project adapts: either wait for small models to improve (12-24 months), or reframe intelligent components as using a remote inference layer.

This failure response plan exists to prevent the all-too-common pattern of "the experiment didn't work; let's just try more things." Pre-committed responses make the experiment honest.

---

## 13. If successful: expansion path

If the first LoRA succeeds, the natural follow-ups (in order, deferred until the previous one validates):

**Generalization test.** Build a second domain claim (Intelligent Provider Registry, once disintegration work is done). Test the existing LoRA against requests using that claim. Does it refuse correctly without retraining? If yes, we have evidence that DCP reasoning generalizes. If no, multi-claim training is required.

**Second behavior LoRA.** Add a second reasoning behavior — most likely conflict detection between claims, since it's the next most distinctive DCP behavior. New training data, new evaluation set, separate experiment.

**Composition reasoning.** Once two or more behaviors validate individually, test whether they compose — can the model handle a request that requires both refusal and dependency analysis? This is the harder integration test.

These follow-ups are explicitly *out of scope* for the current experiment. The first LoRA is one thing only: does refusal work?

---

## 14. Environment and timeline

**Environment:** Local development with sufficient GPU for QLoRA training of 7B models. A 24GB consumer GPU (RTX 4090, A6000) should suffice; smaller GPUs may require additional quantization or gradient accumulation adjustments.

**Realistic timeline:**

- Baseline setup and base-model evaluation: **3-5 days**
- Training dataset authoring: **2-3 weeks**
- Evaluation dataset authoring (done in parallel with training set, but earlier): **1 week**
- Training runs (initial + likely 2-3 iterations): **3-5 days of wall-clock time, mostly waiting**
- Evaluation and analysis: **3-5 days**

**Total realistic time:** 5-7 weeks from start to first answer. The training dataset authoring is the largest single block and the most underestimated.

Compress only if necessary. Rushing this experiment defeats its purpose; the goal is a reliable signal about model capability, which depends on careful dataset construction.

---

## 15. Naming and artifacts

The LoRA produced by this experiment is named:

```text
intelligent-components-dcp-refusal-reasoner-lora-v1
```

The longer name is deliberate. It documents what the LoRA does (DCP refusal reasoning), what generation it is (v1), and that it's specifically for intelligent components. If the experiment produces multiple iterations, increment the version. If it leads to a different behavior (conflict detection), use a different name.

Do not call it "keycloak-lora" — the LoRA is not Keycloak-specific even though Keycloak is its training claim.

---

## 16. What this experiment does and doesn't decide

**Decides:**

- Whether a 7B-parameter open-weight model can perform DCP refusal reasoning reliably with LoRA fine-tuning
- Whether the DCP draft specification is interpretable at small-model scale
- Whether intelligent components with embedded small models are feasible for at least one core behavior

**Does not decide:**

- Whether other DCP reasoning behaviors (planning, negotiation, code generation) are feasible at small scale (those are follow-up experiments)
- Whether the LoRA generalizes across domain claims (that is the generalization follow-up)
- Whether intelligent components are commercially viable (that is a market question, not a technical one)
- The final shape of DCP (the spec will evolve regardless of LoRA outcomes)

Keep the scope of conclusions matched to the scope of the experiment.

---

## 17. Relationship to the broader plan

This experiment sits at a specific point in the Unfurl Systems portfolio:

- **The publication** launches July 1 regardless of this experiment's outcome.
- **The DAG Orchestrator** launches in autumn regardless of this experiment's outcome.
- **The Wave 0 cleanup and Wave 1 Provider Registry extraction** proceed regardless of this experiment's outcome.
- **The Domain Claim Protocol** is shaped by this experiment but exists as a draft regardless. The protocol's first formal version will be revised after this experiment, whether the LoRA succeeds or fails.
- **The intelligent components piece of the portfolio** depends on this experiment. A successful outcome unlocks the next phase of intelligent component work. An unsuccessful outcome triggers reassessment per §12.

This experiment is consequential but not catastrophic. Its purpose is to inform the next decisions, not to determine project survival.

---

## 18. Status

This is v0.2 of the experiment plan. It revises v0.1 to:

- Reframe the work as an experiment with a defined question, not a production training plan
- Narrow scope from eight reasoning categories to refusal reasoning alone
- Reduce dataset size from 500 to 150-200 training examples
- Add a baseline step before any fine-tuning
- Define success criteria precisely with thresholds
- Add a failure response plan to prevent reactive scope expansion
- Defer multi-claim and multi-behavior expansion to explicit follow-up experiments

The plan is ready to begin execution. The first concrete action is building the evaluation set and running the baseline with the base model.
