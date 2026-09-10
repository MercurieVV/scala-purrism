package fix.vocabulary

import munit.FunSuite
import scala.meta._
import scala.meta.dialects.Scala3

class ScopeCheckSuite extends FunSuite {
  test("extracts a dotted package from a parsed source") {
    val tree = "package com.foo.wiring\nclass A".parse[Source].get
    assertEquals(ScopeCheck.filePackage(tree), "com.foo.wiring")
  }

  test("empty package for a file with no package declaration") {
    val tree = "class A".parse[Source].get
    assertEquals(ScopeCheck.filePackage(tree), "")
  }

  test("inScope true only when a scope pattern matches the file package") {
    val scope = PatternList.compile(List("com\\.foo\\.wiring")).toOption.get
    assert(ScopeCheck.inScope("com.foo.wiring", scope))
    assert(!ScopeCheck.inScope("com.foo.other", scope))
  }

  test("empty scope means the whole module is in scope") {
    assert(ScopeCheck.inScope("com.foo.wiring", PatternList.empty))
    assert(ScopeCheck.inScope("anything.at.all", PatternList.empty))
  }
}
