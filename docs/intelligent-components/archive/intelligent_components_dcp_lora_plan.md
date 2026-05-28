> [!WARNING]
> **Historical document (v0.1) — archived.**
> This file is retained for historical context only and is no longer the active plan.
> Use `../intelligent_components_dcp_lora_plan_v2.md` as the canonical source of truth.

# LoRA / QLoRA Plan for Intelligent Components DCP Reasoner

## 1. Goal

Create a LoRA/QLoRA adapter for Qwen 2.5 that teaches the model to consume **Domain Claim Protocol (DCP)** documents for Intelligent Components.

The model should not simply memorize Keycloak. It should learn how to reason over any Intelligent Component claim.

Primary behavior:

```text
Given a Domain Claim Protocol document,
understand ownership, refusals, dependencies, offers, conflict resolution,
and generate safe integration plans for smart components.
```

The first canonical training artifact is the **Intelligent Keycloak Domain Claim**.

---

## 2. Recommended Base Model

Start small and validate the behavior first.

### First experiment

```text
Qwen/Qwen2.5-Coder-7B-Instruct
```

### Next step

```text
Qwen/Qwen2.5-Coder-14B-Instruct
```

### Later, after dataset quality is proven

```text
Qwen/Qwen2.5-Coder-32B-Instruct
```

Use the **Coder** model because Intelligent Components require generation and reasoning around:

- component manifests
- contracts
- schemas
- adapters
- APIs
- deployment descriptors
- tests
- configuration files

---

## 3. What the LoRA Should Learn

The LoRA should learn the **reasoning pattern**, not just facts about Keycloak.

Training patterns:

```text
DCP Claim + Host Requirement -> Integration Plan
DCP Claim + Requested Capability -> Owned / Refused / Redirected
DCP Claim + Other Component Claim -> Conflict Analysis
DCP Claim + Missing Infrastructure -> Dependency Gap Report
DCP Claim + User Question -> Negotiation Surface Response
```

The model should understand that an Intelligent Component:

- claims a domain
- owns specific decisions
- owns specific state
- exposes offers/interfaces
- refuses out-of-domain concerns
- declares dependencies
- negotiates conflicts
- grounds live-state answers in live APIs or tools

---

## 4. Core Rules the Model Must Learn

Every output should follow these rules:

1. Never assign ownership unless the DCP claim says the component owns that concern.
2. Refuse concerns explicitly listed under `refusals`.
3. Redirect refused concerns to the correct owning component type.
4. Treat exclusive ownership conflicts as blockers.
5. Treat missing required dependencies as blockers or integration gaps.
6. Use `offers` to select appropriate integration surfaces.
7. Never guess current runtime state.
8. Say when live APIs, tools, or retrieval are required.
9. Produce structured answers.
10. Prefer component composition over making one component do everything.

---

## 5. Why the Keycloak Claim Is a Good First Artifact

The Intelligent Keycloak DCP claim already contains the right protocol sections:

- Identity
- Domain Assertion
- Domain Refusals
- Dependencies
- Offers
- Conflict Resolution
- Negotiation Surface
- Metadata

It clearly teaches the model that Intelligent Keycloak owns concerns such as:

- user identity verification
- session lifecycle
- credential validation
- identity provider federation
- realm-scoped user management
- token issuance and introspection

It also teaches strong refusals, such as:

- authorization decisions
- API rate limiting
- audit-log storage
- workflow orchestration outside authentication flow
- non-identity credential storage
- broader UI customization ownership
- deployment and operational secret ownership

This makes it useful for teaching boundary discipline.

---

## 6. Dataset Format

Use an Alpaca-style supervised fine-tuning dataset.

Example format:

```json
[
  {
    "instruction": "Using the provided Domain Claim Protocol document, create an Intelligent Component integration plan.",
    "input": "DCP_CLAIM: ...\n\nHOST_REQUIREMENT: Our SaaS app needs login, tenant-specific users, RBAC, audit logs, and branded login pages.",
    "output": "## Integration Plan\n\nUse Intelligent Keycloak for user identity verification, session lifecycle, credential validation, identity-provider federation, realm-scoped user management, and token issuance.\n\nDo not use Intelligent Keycloak for authorization decisions. RBAC and permission checks should be owned by the host authorization layer or a policy component.\n\nDo not use Intelligent Keycloak for audit-log storage. It can emit authentication events, but an audit component should consume and store them."
  }
]
```

---

## 7. Initial Dataset Categories

Create around **500 examples** for the first LoRA.

| Category | Count |
|---|---:|
| Integration plans | 75 |
| Refusal / boundary decisions | 100 |
| Dependency analysis | 50 |
| Conflict resolution | 50 |
| Negotiation responses | 75 |
| Adapter / code skeletons | 50 |
| Live-state grounding | 50 |
| Multi-component composition | 50 |

Total: **500 examples**

---

## 8. Example Training Records

### 8.1 Integration Plan Example

```json
{
  "instruction": "Create an integration plan using the Intelligent Keycloak DCP claim.",
  "input": "Host system needs SSO, MFA, token issuance, user permission checks, and audit-log storage.",
  "output": "Use Intelligent Keycloak for SSO, MFA through authentication flows, token issuance, session lifecycle, and identity-provider federation. Do not assign user permission checks to Intelligent Keycloak because authorization decisions are outside its domain. Use a policy engine or host authorization layer. Do not assign audit-log storage to Intelligent Keycloak. It may emit authentication events, but an audit component should store, query, and retain them."
}
```

### 8.2 Refusal Behavior Example

```json
{
  "instruction": "Decide whether the requested capability belongs to Intelligent Keycloak.",
  "input": "The host wants Keycloak to decide whether Alice can approve an invoice.",
  "output": "Refused. This is an authorization decision, not an identity decision. Intelligent Keycloak can authenticate Alice and issue claims, but the decision of whether Alice can approve an invoice belongs to an authorization component, policy engine, or the host application's authorization layer."
}
```

### 8.3 Dependency Gap Example

```json
{
  "instruction": "Analyze whether the host satisfies Intelligent Keycloak dependencies.",
  "input": "The host provides PostgreSQL, TLS ingress, Kubernetes Secrets, and OpenTelemetry. It does not provide a reasoning model provider.",
  "output": "The host satisfies the required relational database dependency, TLS termination dependency, and deployment secret store dependency. Observability is also available as a recommended dependency. The missing required dependency is a reasoning model provider. Standard Keycloak integration can proceed, but the intelligent negotiation surface requires a reasoning model through a provider abstraction."
}
```

### 8.4 Conflict Resolution Example

```json
{
  "instruction": "Detect domain conflicts using the Intelligent Keycloak DCP claim.",
  "input": "Another component claims exclusive ownership of user-identity-verification for the same realm.",
  "output": "This is a blocking conflict. Intelligent Keycloak claims exclusive ownership of user-identity-verification for users in managed realms. If another component claims the same concern for the same realm scope, the deployment must choose one source of truth."
}
```

### 8.5 Negotiation Surface Example

```json
{
  "instruction": "Respond as the Intelligent Keycloak negotiation surface.",
  "input": "I need a realm for a SaaS product where tenants should have isolated user pools.",
  "output": "There are two main patterns. The first is separate realms per tenant, which gives stronger isolation but creates more operational overhead. The second is one shared realm with tenant-scoped attributes, which is simpler operationally but weaker from an isolation perspective. If strict isolation and compliance are more important, use separate realms. If operational simplicity is more important, use a shared realm with strict tenant claim enforcement."
}
```

### 8.6 Live-State Grounding Example

```json
{
  "instruction": "Answer according to the grounding guarantees in the DCP claim.",
  "input": "How many realms currently exist in this Keycloak instance?",
  "output": "This cannot be answered from training data. The current realm list must be retrieved from the live Keycloak instance through the admin API or an approved introspection tool. The model should not guess."
}
```

---

## 9. Recommended Project Structure

Inside WSL2 Ubuntu:

```bash
mkdir intelligent-components-lora
cd intelligent-components-lora

mkdir data
mkdir output
```

Files:

```text
data/intelligent_components_dcp_train.json
data/intelligent_components_dcp_eval.json
```

Keep evaluation data separate from training data.

---

## 10. Install LLaMA-Factory

```bash
sudo apt update
sudo apt install git python3-venv python3-pip -y

git clone https://github.com/hiyouga/LLaMA-Factory.git
cd LLaMA-Factory

python3 -m venv .venv
source .venv/bin/activate

pip install -U pip
pip install -e ".[torch,metrics]"
```

---

## 11. Register Dataset in LLaMA-Factory

Copy the dataset:

```bash
cp ../data/intelligent_components_dcp_train.json data/
```

Edit:

```bash
nano data/dataset_info.json
```

Add this dataset entry:

```json
{
  "intelligent_components_dcp": {
    "file_name": "intelligent_components_dcp_train.json",
    "formatting": "alpaca",
    "columns": {
      "prompt": "instruction",
      "query": "input",
      "response": "output"
    }
  }
}
```

If `dataset_info.json` already has content, add this as one more dataset entry instead of replacing everything.

---

## 12. QLoRA Training Command

### 12.1 First experiment: 7B

```bash
llamafactory-cli train \
  --stage sft \
  --do_train true \
  --model_name_or_path Qwen/Qwen2.5-Coder-7B-Instruct \
  --dataset intelligent_components_dcp \
  --template qwen \
  --finetuning_type lora \
  --quantization_bit 4 \
  --lora_target all \
  --output_dir saves/qwen2_5_coder_7b/lora/intelligent_components_dcp \
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
  --save_steps 100 \
  --fp16 true
```

### 12.2 Next experiment: 14B

```bash
llamafactory-cli train \
  --stage sft \
  --do_train true \
  --model_name_or_path Qwen/Qwen2.5-Coder-14B-Instruct \
  --dataset intelligent_components_dcp \
  --template qwen \
  --finetuning_type lora \
  --quantization_bit 4 \
  --lora_target all \
  --output_dir saves/qwen2_5_coder_14b/lora/intelligent_components_dcp \
  --overwrite_cache true \
  --overwrite_output_dir true \
  --cutoff_len 4096 \
  --per_device_train_batch_size 1 \
  --gradient_accumulation_steps 16 \
  --learning_rate 1e-4 \
  --num_train_epochs 3 \
  --lr_scheduler_type cosine \
  --warmup_ratio 0.1 \
  --logging_steps 5 \
  --save_steps 100 \
  --fp16 true
```

The output is a LoRA adapter, not a complete model.

Example output folder:

```text
saves/qwen2_5_coder_7b/lora/intelligent_components_dcp
```

---

## 13. Test the Adapter

Use LLaMA-Factory chat:

```bash
llamafactory-cli chat \
  --model_name_or_path Qwen/Qwen2.5-Coder-7B-Instruct \
  --adapter_name_or_path saves/qwen2_5_coder_7b/lora/intelligent_components_dcp \
  --template qwen \
  --finetuning_type lora
```

Test prompt:

```text
Using the Intelligent Keycloak domain claim, should the component own invoice approval decisions?
```

Expected answer:

```text
No. That is an authorization or business-policy decision. Intelligent Keycloak authenticates users and issues identity claims, but approval authorization belongs to a policy component or host application.
```

---

## 14. Merge LoRA into the Base Model

After validating the adapter:

```bash
llamafactory-cli export \
  --model_name_or_path Qwen/Qwen2.5-Coder-7B-Instruct \
  --adapter_name_or_path saves/qwen2_5_coder_7b/lora/intelligent_components_dcp \
  --template qwen \
  --finetuning_type lora \
  --export_dir output/qwen2_5_coder_7b_intelligent_components_dcp \
  --export_size 2 \
  --export_device cpu \
  --export_legacy_format false
```

---

## 15. Serve the Merged Model with vLLM

```bash
vllm serve output/qwen2_5_coder_7b_intelligent_components_dcp \
  --host 0.0.0.0 \
  --port 8000
```

Then call it from the Intelligent Components backend using an OpenAI-compatible API.

---

## 16. Runtime Architecture

Do not bake every DCP claim into the model.

Use LoRA to teach the model the reasoning behavior. Keep actual component claims in a registry.

```text
DCP Claim Registry
  ├── intelligent-keycloak.yaml
  ├── intelligent-audit.yaml
  ├── intelligent-authorization.yaml
  ├── intelligent-provider-registry.yaml
  └── intelligent-workflow-orchestrator.yaml
```

Runtime flow:

```text
User request
   ↓
Retrieve relevant DCP claims
   ↓
Prompt fine-tuned Qwen with those claims
   ↓
Model produces:
   - integration plan
   - boundary analysis
   - dependency gaps
   - conflict report
   - adapter skeleton
```

---

## 17. Evaluation Plan

Create a separate evaluation set with at least 100 examples.

Evaluation categories:

| Evaluation Area | What to Check |
|---|---|
| Ownership reasoning | Does the model assign concerns only when the claim owns them? |
| Refusal quality | Does it refuse authorization, audit storage, rate limiting, etc. correctly? |
| Dependency analysis | Does it detect missing required dependencies? |
| Conflict detection | Does it block exclusive ownership conflicts? |
| Negotiation quality | Does it respond like a component negotiation surface? |
| Grounding discipline | Does it avoid guessing live state? |
| Composition | Does it route work to other components instead of overclaiming? |
| Code generation | Does it generate adapter skeletons without violating domain boundaries? |

---

## 18. Naming

Do not call the adapter:

```text
keycloak-lora
```

Recommended name:

```text
intelligent-components-dcp-reasoner-lora
```

Reason: the purpose is not to train Keycloak knowledge. The purpose is to train DCP claim reasoning for Intelligent Components.

---

## 19. Recommended First Milestone

First milestone:

```text
Model: Qwen/Qwen2.5-Coder-7B-Instruct
Method: QLoRA
Dataset: 500 DCP reasoning examples
Goal: DCP claim consumption and boundary reasoning
Output: intelligent-components-dcp-reasoner-lora
```

Success criteria:

- The model correctly refuses out-of-domain concerns.
- The model identifies required dependency gaps.
- The model detects exclusive ownership conflicts.
- The model generates structured integration plans.
- The model does not hallucinate live Keycloak state.
- The model composes with other Intelligent Components instead of making Keycloak own everything.

---

## 20. Long-Term Direction

After the first Keycloak-based LoRA is validated, add more DCP claims:

- Intelligent Audit Component
- Intelligent Authorization / Policy Component
- Intelligent Provider Registry
- Intelligent Notification Component
- Intelligent Workflow Orchestrator
- Intelligent Theme / UI Morphing Component
- Intelligent Tenant Registry

Then create multi-component training examples.

Example host requirement:

```text
The host needs login, RBAC, audit logging, tenant isolation, and branded login screens.
```

Expected composition:

```text
- Intelligent Keycloak for authentication and identity claims.
- Authorization component for permission decisions.
- Audit component for audit storage, query, retention, and compliance reports.
- Tenant registry for tenant ownership and lifecycle.
- Theme component or host UI system for design language and accessibility.
```

This is where the model starts to understand Intelligent Components as a composable ecosystem rather than isolated integrations.
