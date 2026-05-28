# Step-by-Step Execution Plan: DCP Refusal Reasoning LoRA Experiment

## 1. Purpose

This plan converts the **Intelligent Components DCP Refusal Reasoning LoRA v0.2 experiment** into an execution checklist.

The experiment answers one focused question:

> Can a 7B open-weight model learn reliable Domain Claim Protocol (DCP) refusal and boundary reasoning using QLoRA?

This is an experiment, not a production fine-tuning plan. The LoRA produced here should be treated as instrumentation to generate evidence.

---

## Phase 0 — Lock the Experiment Scope

### Goal

Prevent scope creep.

The experiment tests only one behavior:

```text
DCP refusal and boundary reasoning
```

Do not include these in the first experiment:

- integration planning
- code generation
- multi-component composition
- conflict detection
- dependency analysis
- negotiation quality
- production deployment

### Output

Create:

```text
experiment_scope.md
```

Include:

```text
Experiment: DCP refusal reasoning
Model size: 7B
Claim basis: Intelligent Keycloak only
Training method: QLoRA
Primary output: evidence, not production model
```

---

## Phase 1 — Prepare the Source Material

### Goal

Create a clean source-of-truth folder.

### Folder structure

```bash
mkdir dcp-refusal-experiment
cd dcp-refusal-experiment

mkdir claims
mkdir datasets
mkdir prompts
mkdir runs
mkdir results
mkdir scripts
mkdir reports
mkdir models
```

### Place files

```text
claims/keycloak-domain-claim-example.md
claims/dcp-v0.1.md
prompts/baseline-system-prompt.md
datasets/eval_refusal_v1.json
datasets/train_refusal_v1.json
```

### Rule

The first experiment uses only one claim:

```text
Intelligent Keycloak Domain Claim
```

Reason: one claim keeps the first result easier to interpret and avoids multi-claim confusion.

---

## Phase 2 — Define the Scoring Rubric Before Writing Examples

### Goal

Make evaluation honest before training starts.

Create:

```text
reports/scoring_rubric.md
```

### Primary success criteria

| Metric | Success Threshold |
|---|---:|
| Refusal precision | >= 90% |
| Refusal recall | >= 85% |
| Acceptance precision | >= 90% |
| Redirection accuracy | >= 70% |
| Hallucination rate | <= 5% |

### Failure thresholds

```text
Refusal precision below 75% = clear failure
Refusal recall below 75% = clear failure
Hallucination above 10% = unsafe regardless of other scores
```

### Important rule

Do not change these thresholds after seeing model results.

---

## Phase 3 — Build the Evaluation Dataset First

### Goal

Create a held-out test set before creating training data.

Create:

```text
datasets/eval_refusal_v1.json
```

### Dataset size

Total examples:

```text
50
```

Distribution:

```text
20 clear refusal cases
20 clear acceptance cases
10 edge cases
```

### Evaluation example format

```json
{
  "id": "eval-001",
  "category": "refusal",
  "input": "The host wants Intelligent Keycloak to decide whether Alice can approve an invoice.",
  "expected_disposition": "refuse",
  "expected_redirection": "authorization component or policy engine",
  "rationale": "Invoice approval is an authorization/business-policy decision, not an identity decision."
}
```

### Rules

- Build the evaluation set before the training set.
- Never include evaluation examples in training.
- Avoid near-duplicates between training and evaluation.
- Edge cases are diagnostic, not the primary pass/fail signal.

---

## Phase 4 — Create the Baseline Prompt

### Goal

Check whether fine-tuning is needed at all.

Create:

```text
prompts/baseline-system-prompt.md
```

### Baseline prompt shape

```text
You are a DCP refusal-reasoning evaluator.

Given:
1. A Domain Claim Protocol document
2. A user request

Decide whether the component should accept, refuse, or partially accept the request.

Rules:
- Accept only if the claim owns the concern.
- Refuse if the request falls under the claim's refusals.
- Redirect refused concerns to the likely owning component.
- Do not invent ownership, dependencies, or capabilities.
- If current runtime state is requested, say it requires live system introspection.
```

### Prompt context

The baseline prompt should include or retrieve:

- DCP v0.1 draft
- Intelligent Keycloak Domain Claim
- The user request being tested

---

## Phase 5 — Run the Pre-Fine-Tuning Baseline

### Goal

Establish whether base Qwen already performs well.

### Models to test

```text
Qwen/Qwen2.5-7B-Instruct
Qwen/Qwen2.5-Coder-7B-Instruct
```

### Capture output for each evaluation case

```json
{
  "id": "eval-001",
  "model": "Qwen/Qwen2.5-7B-Instruct",
  "prompt_version": "baseline-system-prompt-v1",
  "model_output": "...",
  "parsed_disposition": "refuse",
  "human_disposition_score": "correct",
  "redirection_score": "correct",
  "hallucination": false
}
```

### Save results

```text
results/baseline_qwen_7b_instruct.json
results/baseline_qwen_7b_coder_instruct.json
reports/baseline_analysis.md
```

### Decision gate

```text
If the base model already scores very high:
    Document that LoRA may not be justified.

If the base model is weak or inconsistent:
    Proceed to QLoRA.
```

The LoRA must meaningfully beat this baseline to be considered useful.

---

## Phase 6 — Select the Base Model for LoRA

### Goal

Pick one 7B model for the first LoRA.

### Decision rule

```text
If one model clearly beats the other on refusal metrics:
    Use that model.

If both are similar:
    Prefer Qwen/Qwen2.5-7B-Instruct for this first refusal experiment.
```

Reason: refusal reasoning is more about intent and domain-boundary understanding than code generation.

### Do not use yet

```text
Qwen/Qwen2.5-14B-Instruct
Qwen/Qwen2.5-Coder-14B-Instruct
Qwen/Qwen2.5-32B-Instruct
Qwen/Qwen2.5-Coder-32B-Instruct
```

The value of the experiment is first understanding what 7B can do.

---

## Phase 7 — Build the Training Dataset

### Goal

Author high-quality training examples focused only on refusal reasoning.

Create:

```text
datasets/train_refusal_v1.json
```

### Dataset size

```text
150-200 examples
```

### Distribution

```text
80 refusal examples
60 acceptance examples
30 edge cases
10 hallucination-prevention examples
```

### Training example format

```json
{
  "instruction": "Using the Intelligent Keycloak DCP claim, decide whether the request should be accepted, refused, or partially accepted.",
  "input": "The host wants Intelligent Keycloak to store API keys for external AI providers.",
  "output": "Refuse. Storing credentials for external AI providers is not an identity concern. Intelligent Keycloak's claim refuses credential storage for non-identity purposes. This should be owned by a provider registry, per-component credential store, or operational secret store."
}
```

### Quality rule

150 carefully written examples are better than 500 mechanical examples.

---

## Phase 8 — Add Dataset Quality Checks

### Goal

Prevent leakage and weak examples.

Create:

```text
reports/dataset_quality_checklist.md
```

### Checklist for every training example

```text
[ ] Does it test one clear reasoning pattern?
[ ] Is the expected answer grounded in the Keycloak claim?
[ ] Does it avoid evaluation-set wording?
[ ] Does it avoid teaching broad Keycloak facts unrelated to refusal?
[ ] Does it avoid making the model over-refuse?
[ ] Does it redirect correctly when refusal is expected?
[ ] Does it avoid inventing ownership not present in the claim?
```

### Optional script

Create:

```text
scripts/check_train_eval_overlap.py
```

Purpose:

```text
Detect duplicate or near-duplicate examples between train_refusal_v1.json and eval_refusal_v1.json.
```

---

## Phase 9 — Prepare LLaMA-Factory Setup

### Goal

Set up QLoRA training environment.

### Install

```bash
git clone https://github.com/hiyouga/LLaMA-Factory.git
cd LLaMA-Factory

python3 -m venv .venv
source .venv/bin/activate

pip install -U pip
pip install -e ".[torch,metrics]"
```

### Copy dataset

```bash
cp ../datasets/train_refusal_v1.json data/
```

### Register dataset

Edit:

```text
data/dataset_info.json
```

Add:

```json
{
  "intelligent_components_dcp_refusal": {
    "file_name": "train_refusal_v1.json",
    "formatting": "alpaca",
    "columns": {
      "prompt": "instruction",
      "query": "input",
      "response": "output"
    }
  }
}
```

If `dataset_info.json` already has content, add this as one more dataset entry instead of replacing the file.

---

## Phase 10 — Run QLoRA Training

### Goal

Train the first refusal-reasoning adapter.

Use the selected model from the baseline step.

### Training command

```bash
llamafactory-cli train \
  --stage sft \
  --do_train true \
  --model_name_or_path Qwen/Qwen2.5-7B-Instruct \
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

### Notes

- Use `save_steps 50` because the dataset is small.
- Keep epochs at 3 initially.
- If loss plateaus early, try 2 epochs in the next iteration.
- Do not merge the LoRA into the base model yet.

### Output

```text
saves/qwen2_5_7b/lora/dcp_refusal_v1
```

---

## Phase 11 — Evaluate the LoRA Adapter

### Goal

Run the same 50 held-out evaluation examples against the adapted model.

Do not merge the LoRA.

### Capture result for each example

```json
{
  "id": "eval-001",
  "model": "qwen2.5-7b+dcp_refusal_v1",
  "model_output": "...",
  "expected_disposition": "refuse",
  "actual_disposition": "refuse",
  "disposition_correct": true,
  "redirection_correct": true,
  "hallucination": false,
  "notes": ""
}
```

### Compute metrics

```text
refusal_precision
refusal_recall
acceptance_precision
redirection_accuracy
hallucination_rate
```

### Save

```text
results/lora_v1_eval_results.json
reports/lora_v1_evaluation_report.md
```

---

## Phase 12 — Make the Experiment Decision

### Goal

Decide honestly based on the predefined criteria.

### Decision table

| Result | Action |
|---|---|
| All primary criteria met and meaningful baseline improvement | Clear success |
| Most criteria met but some weak areas | Qualified success |
| Major criteria missed | Failure path |
| Hallucination > 10% | Unsafe; treat as failure |
| Base model already strong and LoRA only marginally better | Fine-tuning may not be justified |

### Evaluation report contents

Create:

```text
reports/lora_v1_evaluation_report.md
```

Include:

```text
Baseline scores
LoRA scores
Delta improvement
Failure modes
Representative good outputs
Representative bad outputs
Decision
Recommended next step
```

---

## Phase 13 — If Qualified Success, Iterate Once or Twice

### Goal

Improve targeted weaknesses without expanding scope.

### Targeted fixes

If redirection accuracy is weak:

```text
Add 30-50 redirection-focused examples.
```

If over-refusal is high:

```text
Add more acceptance examples.
```

If under-refusal is high:

```text
Add more refusal examples around missed concern types.
```

If hallucination is high:

```text
Add more "claim does not say this" examples.
```

### New adapter versions

```text
dcp_refusal_v2
dcp_refusal_v3
```

### Rule

Do not add new behaviors yet.

---

## Phase 14 — If Failure, Follow the Pre-Committed Failure Plan

### Goal

Avoid reactive experimentation.

### Failure response sequence

1. Diagnose the failure mode.
2. Add targeted examples.
3. Try 14B only if targeted expansion fails.
4. Reconsider the DCP structure if both 7B and 14B fail.
5. Accept the negative result if refusal reasoning still does not work.

### Interpretation

A failure does not invalidate the whole Unfurl Systems portfolio.

It only means one of the following may be true:

- embedded small-model Intelligent Components are not yet feasible
- the protocol needs to be more structured
- larger models are required
- a remote inference layer may be needed

---

## Phase 15 — If Successful, Run a Generalization Test

### Goal

Check whether the LoRA learned DCP reasoning or only Keycloak-specific behavior.

### Next experiment

Create a second claim:

```text
Intelligent Provider Registry
```

Then:

```text
Use existing LoRA without retraining
Build 30-50 evaluation examples for the second claim
Test refusal behavior
```

### Outcomes

```text
Passes second claim:
    Evidence that DCP refusal reasoning may generalize.

Fails second claim:
    Multi-claim training is required.
```

### Rule

This is a follow-up experiment, not part of the first LoRA.

---

## Recommended Timeline

| Week | Work |
|---|---|
| Week 1 | Finalize scoring rubric, build 50-example eval set, create baseline prompt |
| Week 2 | Run baseline on Qwen 7B and Qwen 7B Coder, analyze results |
| Weeks 3-4 | Author 150-200 training examples |
| Week 5 | Train QLoRA v1, evaluate, write report |
| Week 6 | Targeted iteration if needed |
| Week 7 | Final decision: success, qualified success, failure, or no-LoRA-needed |

Total realistic time:

```text
5-7 weeks
```

The largest and most underestimated block is dataset authoring.

---

## Concrete Artifact Checklist

By the end of the experiment, the folder should contain:

```text
claims/
  keycloak-domain-claim-example.md
  dcp-v0.1.md

prompts/
  baseline-system-prompt.md

datasets/
  eval_refusal_v1.json
  train_refusal_v1.json

results/
  baseline_qwen_7b_instruct.json
  baseline_qwen_7b_coder_instruct.json
  lora_v1_eval_results.json

reports/
  experiment_scope.md
  scoring_rubric.md
  dataset_quality_checklist.md
  baseline_analysis.md
  lora_v1_evaluation_report.md
  final_experiment_decision.md

scripts/
  check_train_eval_overlap.py

models/
  intelligent-components-dcp-refusal-reasoner-lora-v1
```

---

## Most Important First Action

Do not start with training.

Start with:

```text
Build the 50-example evaluation set.
```

That evaluation set is the anchor of the experiment.

Without it, the LoRA result will be subjective.

---

## Final Naming

The first LoRA should be named:

```text
intelligent-components-dcp-refusal-reasoner-lora-v1
```

Do not call it:

```text
keycloak-lora
```

The claim basis is Keycloak, but the experiment is about DCP refusal reasoning for Intelligent Components.
