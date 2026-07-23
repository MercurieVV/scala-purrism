package golden.typelevel

final case class AgentTool(
    id: String,
    agent: String
)

object AgentInventory:
  private val Fallback =
    AgentTool(
      id = "claude-opus",
      agent = "claude"
    )
