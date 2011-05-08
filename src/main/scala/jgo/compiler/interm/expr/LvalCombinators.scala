package jgo.compiler
package interm
package expr

import message._
import message.Messaged._

import types._
import instr._
import instr.TypeConversions._
import codeseq._

import Utils._

private trait LvalCombinators extends Combinators with TypeChecks {
  private def lval(e: Expr, desc: String) (implicit pos: Pos): M[LvalExpr] = e match {
    case l: LvalExpr => Result(l)
    case _ => Problem("lvalue expected for %s", desc)
  }
  private def mkPtr(e: Expr, desc: String) (implicit pos: Pos): M[Expr] =
    if (e.addressable) Result(e.mkPtr)
    else Problem("addressable expression expected for %s", desc)
  
  
  def addrOf(e: Expr) (implicit pos: Pos): M[Expr] = for {
    p <- mkPtr(e, "operand")
  } yield p
  
  def deref(e: Expr) (implicit pos: Pos): M[PtrDerefLval] = for {
    res <- e match {
      case HasType(PointerType(elemT)) => Result(PtrDerefLval(e, elemT))
      case _ =>
        Problem("operand of pointer indirection/dereference has type %s; pointer type required", e.t) 
    }
  } yield res
  
  //def select(obj: Expr, selector: String) (implicit pos: Pos): M[Expr]
  
  
  private def mkIndex(arr: Expr, indx: Expr) (implicit pos: Pos) = {
    @inline def isIntegral = indx.isOfType[IntegralType]
    
    arr match {
      case HasType(ArrayType(_, elemT)) =>
        if (isIntegral) Result(ArrayIndexLval(arr, indx, elemT))
        else Problem("index type %s is inappropriate for an array; integral type required", indx.t)
      case HasType(SliceType(elemT)) =>
        if (isIntegral) Result(SliceIndexLval(arr, indx, elemT))
        else Problem("index type %s is inappropriate for a slice; integral type required", indx.t)
      case HasType(StringType) =>
        if (isIntegral) Result(BasicExpr(arr.eval |+| indx.eval |+| StrIndex, Uint8))
        else Problem("index type %s is inappropriate for a string; integral type required", indx.t)
      case HasType(MapType(keyT, valT)) =>
        if (keyT <<= indx.t) Result(MapIndexLval(arr, indx, valT))
        else Problem(
          "index type %s is inappropriate for a map of type %s; must be assignable to key type %s",
          indx.t, arr.t, keyT
        )
    }
  }
  def index(arr: Expr, indx: Expr) (implicit pos: Pos): M[Expr] = for {
    result <- mkIndex(arr, indx)
  } yield result
  
  def slice(arr: Expr, low: Option[Expr], high: Option[Expr]) (implicit pos: Pos): M[Expr] =
    for {
      _ <- together(
        for (l <- low)  yield integral(l, "lower bound of slice"), //these Option[M[Expr]]'s are implicitly
        for (h <- high) yield integral(h, "upper bound of slice")  //converted to M[Option[Expr]]'s
      )
      (stackingCode, boundsInfo) = (low, high) match {
        case (Some(e1), Some(e2)) => (e1.eval |+| e2.eval, BothBounds)
        case (Some(e1), None)     => (e1.eval            , LowBound)
        case (None,     Some(e2)) => (            e2.eval, HighBound)
        case (None,     None)     => (CodeBuilder(),       NoBounds)
      }
      result: Expr <- arr match {
        case HasType(ArrayType(_, t)) => Result(BasicExpr(stackingCode |+| SliceArray(t, boundsInfo), t))
        case HasType(SliceType(t))    => Result(BasicExpr(stackingCode |+| SliceSlice(t, boundsInfo), t))
        case HasType(StringType)      => Result(BasicExpr(stackingCode |+| Substring(boundsInfo), StringType))
        case _ => Problem("cannot slice a value of type %s; array, slice, or string type required", arr.t)
      }
    } yield result
  
  def incr(e: Expr) (implicit pos: Pos): M[CodeBuilder] = for {
    l <- lval(e, "operand of ++")
    (_, intT) <- integral(l, "operand of ++") //NOTE:  Need to fix this; an "intLval" check needed
  } yield l match {
    case VarLval(vr) => Incr(vr, 1, intT)
    case _ => l.store(l.load |+| PushInt(1, intT) |+| Add(intT))
  }
  
  def decr(e: Expr) (implicit pos: Pos): M[CodeBuilder] = for {
    l <- lval(e, "operand of --")
    (_, intT) <- integral(l, "operand of --")
  } yield l match {
    case VarLval(vr) => Decr(vr, 1, intT)
    case _ => l.store(l.load |+| PushInt(1, intT) |+| Sub(intT))
  }
  
  
  
  private def lvalues(es: List[Expr], desc: String) (implicit pos: Pos): M[List[LvalExpr]] =
    for {
      (e, i) <- es.zipWithIndex
    } yield for {
      l <- lval(e, "%s term of %s".format(ordinal(i + 1), desc))
    } yield l
    
    //the implicit conversion Messaged.lsM2mLs lifts that List[M[LvalExpr]] to M[List[..]]
    
  private def zipAndCheckArity[A](as: List[A], bs: List[Expr]) (implicit pos: Pos): M[List[(A, Expr)]] = {
    var res: List[(A, Expr)] = Nil
    var (curA, curB) = (as, bs)
    var lenA, lenB = 0
    while (!curA.isEmpty || !curB.isEmpty) {
      if (curA isEmpty) {
        lenB += curB.length
        return Problem("Arity (%d) of left side of assignment unequal to arity (%d) of right side", lenA, lenB)
      }
      if (curB isEmpty) {
        lenA += curA.length
        return Problem("Arity (%d) of left side of assignment unequal to arity (%d) of right side", lenA, lenB)
      }
      val pair = (curA.head, curB.head)
      curA = curA.tail
      curB = curB.tail
      res = pair :: res
    }
    Result(res reverse)
  }
  private def checkAssignability(pairs: List[(LvalExpr, Expr)]) (implicit pos: Pos): M[Unit] = {
    for (((l, r), i) <- pairs zipWithIndex)
      if (!(l.t <<= r.t))
        return Problem(
          "%s value on right side of assignment has type %s not assignable to corresponding " +
          "target type %s", ordinal(i), r.t, l.t
        )
    Result(())
  }
  def assign(left0: List[Expr], right: List[Expr]) (implicit pos: Pos): M[CodeBuilder] = for {
    left <- lvalues(left0, "left side of assignment")
    pairs <- zipAndCheckArity(left, right)
    _ <- checkAssignability(pairs)
  } yield {
    val (leftCode, rightCode) = (pairs foldLeft (CodeBuilder.empty, CodeBuilder.empty)) {
      case ((leftAcc, rightAcc), (l, r)) => (leftAcc |+| l.storePrefix(r.eval), l.storeSuffix |+| rightAcc)
    }
    leftCode |+| rightCode
  }
}
