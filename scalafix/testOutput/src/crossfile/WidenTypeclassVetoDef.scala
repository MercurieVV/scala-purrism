
package crossfile

import cats.Show

object WidenTypeclassVetoDef {
  def summarise[A: Show](rows: List[Int]): List[String] = 
    rows.map(row => row.toString)
}
