package golden.typelevel

opaque type AgentToolId = String
object AgentToolId:
  def apply(value: String): AgentToolId = value
  extension (opaqueValue: AgentToolId) def value: String = opaqueValue
  given cats.Eq[AgentToolId] = cats.Eq.by(_.value)

final case class AgentTool(
    id: AgentToolId,
    agent: String
)

object AgentInventory:
  private val Fallback =
    AgentTool(
      id = AgentToolId("claude-opus"),
      agent = "claude"
    )
