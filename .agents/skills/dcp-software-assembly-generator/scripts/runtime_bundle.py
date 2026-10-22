"""Projector: generate native Flow/Foundry authoring artifacts without registering or executing them."""

import argparse
import json
from pathlib import Path

import suite


def write(root, name, value):
    """Serializer: create one inspectable JSON resource without overwriting an existing artifact."""
    path = suite.confined(root, name)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, indent=2)
        stream.write("\n")


def workflow(operation, connector):
    """Builder: project mandatory preflight/call/post gates into canonical Flow node records."""
    nodes = []
    for step, route, dependencies in (("preflight", "preflight", []), ("call", "calls", ["preflight_gate"])):
        nodes.append({"id": step, "type": "ACTION", "uses": "connector.request",
                      "name": step, "description": "Host-bound guarded authoring API call",
                      "config": {"connector": connector, "method": "POST", "path": f"/{route}/{operation}"},
                      "input": {"body": "$.workflow.input.request"}, "dependencies": dependencies,
                      "retry": {"maxAttempts": 1, "backoffMs": 0}, "onFailure": "FAIL"})
        nodes.append({"id": step + "_gate", "type": "ACTION", "uses": "tool.result.gate",
                      "config": {"expectedStatus": "PASS"}, "dependencies": [step],
                      "input": {"result": f"$.nodes.{step}.output"}, "onFailure": "FAIL"})
    return {"id": operation.replace('.', '-'), "version": "1.0.0", "metadata": {
        "name": operation, "description": "Preflight and mandatory transport hooks; GAP blocks downstream work",
        "labels": {"owner": "dcp-authoring", "requiresConnectorBinding": connector}},
        "inputSchema": {"type": "object", "required": ["request"], "properties": {"request": {
            "type": "object", "required": ["callId", "arguments"], "additionalProperties": False,
            "properties": {"callId": {"type": "string"}, "arguments": {"type": "object"}}}}},
        "outputSchema": {}, "nodes": nodes, "edges": []}


def application_graphs():
    """Builder: author the Kotak capability graph with bounded fan-out and explicitly unresolved providers."""
    def action(name, uses, values, dependencies=(), config=None, template=False):
        """Node Factory: keep native dependencies and one-attempt effect semantics explicit."""
        return {"id": name, "type": "ACTION", "uses": uses, "input": values,
                "config": config or {}, "dependencies": list(dependencies), "template": template,
                "onFailure": "FAIL", "retry": {"maxAttempts": 1, "backoffMs": 0}}

    def fanout(name, source, child, dependencies):
        """MAP Factory: project bounded collection work into Flow's actual template-node model."""
        return {"id": name, "type": "MAP", "config": {"itemsExpression": source,
                "childNodeId": child, "maxConcurrency": 2}, "dependencies": list(dependencies), "onFailure": "FAIL"}

    account = "$.workflow.input.accountRef"
    nodes = [action("connection", "connection.resolve", {"connectionRef": "$.workflow.input.connectionRef"}),
             action("holdings", "portfolio.holdings.list", {"accountRef": account}, ["connection"]),
             fanout("transactions", "$.nodes.holdings.output.items", "transaction_item", ["holdings"]),
             action("transaction_item", "portfolio.transactions.list", {"accountRef": account,
                    "instrument": "$._map.item"}, template=True),
             fanout("prices", "$.nodes.holdings.output.items", "price_item", ["holdings"]),
             action("price_item", "market.52week-range.get", {"instrument": "$._map.item"}, template=True),
             action("eligible_lots", "strategy.eligible-lots.evaluate", {"holdings": "$.nodes.holdings.output",
                    "transactions": "$.nodes.transactions.output"}, ["transactions"]),
             action("proposals", "strategy.gtt-proposals.create", {"eligibleLots": "$.nodes.eligible_lots.output",
                    "prices": "$.nodes.prices.output", "buyBudgets": "$.workflow.input.buyBudgets",
                    "costProfileRef": "$.workflow.input.costProfileRef"}, ["eligible_lots", "prices"],
                    config={"buyLowMultiplier": "1.02", "sellHighMultiplier": "0.99",
                            "minimumSellNetReturn": "0.12", "sellAge": "strictly-more-than-12-months",
                            "warnOnUncertainEstimates": True, "limitEqualsTrigger": True}),
             action("explain", "agent.run", {"agentRef": "$.workflow.input.explainerAgentRef",
                    "input": {"proposals": "$.nodes.proposals.output"}}, ["proposals"]),
             fanout("submit", "$.nodes.proposals.output.proposals", "submit_item", ["explain"]),
             action("submit_item", "subgraph.execute", {"accountRef": account, "proposal": "$._map.item"},
                    config={"subgraphRef": "kotak-gtt-submit-one@1.0.0"}, template=True)]
    submit = [action("approve", "human.approval", {"intent": "$.workflow.input.proposal"}),
              action("approval_gate", "policy.approved-intent.validate", {"intent": "$.workflow.input.proposal",
                     "approval": "$.nodes.approve.output"}, ["approve"]),
              action("dispatch", "gtt.instructions.create", {"accountRef": account,
                     "intent": "$.workflow.input.proposal", "approvalRef": "$.nodes.approval_gate.output.approvalRef"}, ["approval_gate"]),
              action("verify", "gtt.instructions.verify", {"accountRef": account,
                     "acknowledgement": "$.nodes.dispatch.output", "intent": "$.workflow.input.proposal"}, ["dispatch"])]
    return [{"id": identity, "version": "1.0.0", "metadata": {"name": identity,
             "description": "Draft capability graph; missing providers/bindings prohibit activation",
             "labels": {"readiness": "draft", "hookOwner": "host-and-adapter"}},
             "inputSchema": {"type": "object"}, "nodes": values, "edges": []}
            for identity, values in (("kotak-portfolio-gtt", nodes), ("kotak-gtt-submit-one", submit))]


def build(destination, model_ref, connector="authoring-guarded", tenant=None):
    """Builder: emit source-grounded call graphs, bounded reasoning definitions and unresolved bindings."""
    destination = Path(destination).resolve()
    suite.require(isinstance(tenant, str) and tenant.strip(), "an explicit tenant is required")
    suite.require(not destination.exists() or not any(destination.iterdir()), "bundle destination must be empty")
    destination.mkdir(parents=True, exist_ok=True)
    manifest = suite.load(suite.ASSETS / "api-calls.json")
    for operation in manifest["operations"]:
        write(destination, "flow/" + operation + ".workflow.json", workflow(operation, connector))
    for definition in application_graphs():
        write(destination, "application/" + definition["id"] + ".workflow.json", definition)
    roles = {
        "interview": ("Clarify component, first flow, UI and runtime choices. Preserve known answers. Return a structured proposal or questions.",
                      ["fabric.catalog-query", "fabric.file-list", "fabric.session-history"]),
        "gap-analysis": ("Classify missing API wrappers, separate strategy components and connection requirements. Never invent capability support. Captures are API evidence, not a required full customer dataset.",
                         ["fabric.catalog-query", "fabric.file-list"]),
        "governance": ("Offer grounded identity, authorization, audit, monitoring and connection choices. OIDC identity does not grant downstream account access. Never request credential values.",
                       ["fabric.catalog-query", "fabric.session-history"]),
        "graph-authoring": ("Propose canonical native graph definitions. Keep unresolved capabilities/bindings visible. Validate Flow proposals using the available validation tool. Never register or execute a business mutation.",
                            ["flow.workflow-draft-questions", "flow.tool-suggest", "flow.workflow-validate"])}
    hook_policy = {"bindings": ["audit", "redact", "normalizeResult", "prerequisites", "normalize", "approval"],
                   "prerequisiteTokens": {}, "approvalTools": [],
                   "redactedResultKeys": ["password", "otp", "clientSecret", "accessToken", "refreshToken",
                                          "access_token", "refresh_token", "Authorization", "sid", "cookie"]}
    skills = []
    for role, (prompt, tools) in roles.items():
        name = "dcp-" + role
        prompt_ref = "prompts/" + name + ".md"
        output_schema = {"type": "object", "required": ["kind", "assistantMessage"], "properties": {
            "kind": {"enum": ["clarify", "gap", "proposal"]}, "assistantMessage": {"type": "string"},
            "questions": {"type": "array"}, "proposal": {"type": "object"}, "unresolvedNeeds": {"type": "array"}}}
        agent = {"id": name, "version": "1.0.0", "defaultModelRef": model_ref, "toolRefs": tools,
                 "skillRefs": [], "inputSchema": {"type": "object"}, "budgetPolicy": {
                     "maxPromptTokens": 8000, "maxCompletionTokens": 2000, "maxTotalTokens": 10000},
                 "metadata": {"toolPolicy": hook_policy, "modelRefs": [model_ref],
                              "promptTemplateRefs": [prompt_ref], "outputSchemas": {"proposal": output_schema},
                              "contextPolicy": {"retainToolResults": False, "retainProvenance": True}},
                 "phases": [{"id": "propose", "promptTemplateRef": prompt_ref, "modelRef": model_ref,
                             "allowedToolRefs": tools, "input": {"context": "$.input"}, "dependencies": [],
                             "maxToolIterations": 4, "outputSchemaRef": "proposal"}], "edges": []}
        # JSON is valid YAML; the native directory resolver requires the .agent.yaml suffix.
        write(destination, "agents/" + name + ".agent.yaml", agent)
        skill = {"name": name, "version": "1.0.0",
              "description": prompt, "toolRefs": tools, "promptFragmentRef": prompt_ref,
              "ragSourceRefs": [], "defaultModelRef": model_ref, "budgetPolicy": agent["budgetPolicy"],
              "permissionScope": tools, "inputSchema": agent["inputSchema"], "outputSchema": output_schema,
              "metadata": {"boundary": "server-side-only", "advisoryOnly": True}}
        skills.append(skill)
        write(destination, "skills/" + name + ".skill.json", skill)
        path = suite.confined(destination, prompt_ref)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(prompt + "\nUse host-controlled grants; proposals never establish authority.\n", encoding="utf-8")
    write(destination, "hooks/foundry-tool-policy.json", {"metadata": {"toolPolicy": hook_policy}})
    write(destination, "skills/runtime.skills.yaml", {"tenantId": tenant, "skills": skills})
    write(destination, "hooks/foundry-profile.fragment.json", {"bindings": [
          {"kind": "APPROVAL_STORE", "name": "authoring-approvals", "adapter": "file-tool-approval-store",
           "config": {"path": ".foundry/authoring-approvals"}},
          {"kind": "POLICY_CHAIN", "name": "authoring-policy", "adapter": "default-tool-policy-chain",
           "config": {"approvalStore": "authoring-approvals"}}]})
    write(destination, "binding-requirements.json", {"schemaVersion": 1, "status": "draft",
          "connector": connector, "transport": "host-bound AuthoringConnectorTransport",
          "gateway": {"authentication": "BEARER", "credentialOwner": "host", "rawCredentialsAllowed": False},
          "requiredBusinessCapabilities": sorted({node["uses"] for graph in application_graphs()
                                                  for node in graph["nodes"] if node.get("uses")}),
          "modelRef": model_ref, "authoringSequence": ["interview", "gap-analysis", "adapter/strategy Codex handoff",
              "catalog admission", "governance", "accepted intent", "graph-authoring", "compile", "binding/export"],
          "unresolvedNeeds": ["Bind authenticated loopback gateway and pinned host profile",
              "Bind model/provider, prompt resources, scoped tool registry and durable audit/approval ports",
              "Code creation uses local Codex handoff; no unattended development-task service is implied",
              "Kotak transaction/market/GTT write wrappers and separate strategy must be implemented before business execution",
              "Choose project runtime/UI and validate actual DCP contracts and account connections"],
          "businessGraph": ["scoped connection", "holdings wrapper", "transactions wrapper", "remaining-lot strategy",
              "52-week market wrapper", "target/cost/tax strategy", "proposal flags", "Foundry explanation",
              "exact approval", "durable GTT dispatch", "verification", "audit/unknown outcome"]})
    artifacts = [suite.pin(destination, path.relative_to(destination).as_posix(), "generated-resource")
                 for path in sorted(destination.rglob("*")) if path.is_file()]
    write(destination, "bundle-index.json", {"schemaVersion": 1, "status": "draft", "artifacts": artifacts})
    return {"path": str(destination), "workflows": len(manifest["operations"]) + 2, "agents": len(roles), "status": "draft"}


def main(argv=None):
    """CLI Facade: project artifacts only; require deliberate runtime binding and activation later."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--model-ref", required=True)
    parser.add_argument("--tenant", required=True)
    parser.add_argument("--connector", default="authoring-guarded")
    args = parser.parse_args(argv)
    print(json.dumps(build(args.destination, args.model_ref, args.connector, args.tenant), indent=2))


if __name__ == "__main__":
    main()
