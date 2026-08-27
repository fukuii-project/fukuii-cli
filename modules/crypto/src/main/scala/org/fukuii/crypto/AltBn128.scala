package org.fukuii.crypto

/** The pairing-friendly curve `alt_bn128`, and the three operations over it
  * that a chain can answer natively.
  *
  * ==What is here is the curve, not the addresses it answers at==
  *
  * Encoding, refusal and arithmetic, from bytes to bytes. Which addresses run
  * these, at what price and from which height, is a network's and is nowhere in
  * this module.
  *
  * ==The parameters are read from the two documents, and one is DERIVED from
  * them rather than stated by either==
  *
  * `ethereum/EIPs` @ `dbfa6bee83`, `EIPS/eip-196.md` (Final) gives the field and
  * the curve: *"Y^2 = X^3 + 3 over the field F_p"*, with `p` written out.
  * `EIPS/eip-197.md` (Final) in the same tree gives the second group's order
  * `q`, the quadratic extension *"F_p^2 = F_p[i] / (i^2 + 1)"*, and the twist
  * *"Y^2 = X^3 + 3/(i+9)"*.
  *
  * [[Parameter]] is stated by neither. It is the integer both moduli are
  * polynomials in, and it is pinned BY them: `p` and `q` are strictly
  * increasing quartics in it, so at most one positive integer produces the pair
  * the documents state. `AltBn128Spec` asserts both polynomials, which is what
  * makes this a value the two documents determine rather than a constant taken
  * on trust.
  *
  * ==Everything else is computed here rather than written down==
  *
  * The twist coefficient, the maps that carry a point and a field element to
  * their images under the Frobenius endomorphism, and the exponent the pairing
  * ends with are all derived from `p`, `q` and the extension above. A curve
  * implementation ordinarily writes those out as tables of hex; none of them is
  * here, so there is no figure in this file that a reader has to take on trust
  * and no place for a transcription to hide.
  *
  * ==A point at infinity is (0, 0), in the encoding and in memory alike==
  *
  * EIP-196 fixes the encoding: *"the point at infinity is encoded as (0, 0)"*.
  * Nothing distinguishes that pair from a finite point, because there is no
  * finite point it could be: `0^2` is not `0^3 + 3` in either group. So the
  * representation below carries no separate case for it, and a decoded point
  * needs no unwrapping before it is used.
  */
object AltBn128:

  /** The field both groups' coordinates are drawn from.
    *
    * `EIPS/eip-196.md` § Specification and `EIPS/eip-197.md` § Definition of the
    * groups state the same number.
    */
  val FieldModulus: BigInt = BigInt("21888242871839275222246405745257275088696311157297823662689037894645226208583")

  /** The prime order shared by the two groups the pairing is defined on.
    *
    * `EIPS/eip-197.md` § Definition of the groups. The first group's cofactor is
    * one -- the curve over the base field has exactly this many points -- so
    * every point of it that is on the curve is already of this order, which is
    * why only the second group is asked.
    */
  val GroupOrder: BigInt = BigInt("21888242871839275222246405745257275088548364400416034343698204186575808495617")

  /** The width of an encoded point of the first group: two field elements. */
  val G1Width: Int = 64

  /** The width of an encoded point of the second group: four field elements. */
  val G2Width: Int = 128

  /** The width of one pairing operand, a point from each group.
    *
    * The document counts these in *points* -- *"`k` is the length of the input
    * divided by `192`"* -- so one "point" in its gas formula is this whole pair
    * and not one of the two.
    */
  val PairWidth: Int = G1Width + G2Width

  /** The integer both moduli are quartics in.
    *
    * Stated by neither document and determined by both; see this object's
    * documentation for why one value can satisfy the pair.
    */
  private[crypto] val Parameter: BigInt = BigInt("4965661367192848881")

  /** How far the pairing's inner loop counts.
    *
    * `6u + 2`, the count the optimal ate pairing over a curve of this family is
    * defined at. The loop consumes it from its second-highest bit down, its
    * highest standing for the point the loop starts from.
    */
  private val LoopCount: BigInt = 6 * Parameter + 2

  /** The exponent the pairing ends with, after two cheaper factors are taken
    * off.
    *
    * The whole exponent is `(p^12 - 1) / q` and it factors as
    * `(p^6 - 1) * (p^2 + 1) * this`. The first two factors are a conjugation
    * with an inversion and one application of the squared Frobenius, so only
    * this one is paid for by squaring and multiplying -- 761 bits against 2790.
    * `AltBn128Spec` asserts the factorization rather than leaving the three
    * pieces to be trusted separately.
    */
  private[crypto] val HardExponent: BigInt =
    (FieldModulus.pow(4) - FieldModulus.pow(2) + 1) / GroupOrder

  private val WordWidth: Int = 32

  // ---------------------------------------------------------------- the field

  /** An element of `F_p`, reduced.
    *
    * The two rules are that every value handed out is in `[0, p)` and that
    * nothing here reduces a value that is already known to be. Addition and
    * subtraction of reduced values move by less than one modulus, so a compare
    * and a single add settle them; only multiplication needs a division.
    */
  private object Fp:
    def added(left: BigInt, right: BigInt): BigInt =
      val sum = left + right
      if sum >= FieldModulus then sum - FieldModulus else sum

    def subtracted(left: BigInt, right: BigInt): BigInt =
      val difference = left - right
      if difference.signum < 0 then difference + FieldModulus else difference

    def negated(value: BigInt): BigInt = if value.signum == 0 then value else FieldModulus - value

    def multiplied(left: BigInt, right: BigInt): BigInt = (left * right).mod(FieldModulus)

    def inverted(value: BigInt): BigInt = value.modInverse(FieldModulus)

  /** An element `real + imaginary * i` of `F_p[i] / (i^2 + 1)`. */
  final private case class Fp2(real: BigInt, imaginary: BigInt):

    def plus(other: Fp2): Fp2 = Fp2(Fp.added(real, other.real), Fp.added(imaginary, other.imaginary))

    def minus(other: Fp2): Fp2 = Fp2(Fp.subtracted(real, other.real), Fp.subtracted(imaginary, other.imaginary))

    def negated: Fp2 = Fp2(Fp.negated(real), Fp.negated(imaginary))

    /** Three multiplications rather than four: the cross term is recovered from
      * the product of the sums, `i^2` being `-1`.
      */
    def times(other: Fp2): Fp2 =
      val low = Fp.multiplied(real, other.real)
      val high = Fp.multiplied(imaginary, other.imaginary)
      val cross = Fp.multiplied(Fp.added(real, imaginary), Fp.added(other.real, other.imaginary))
      Fp2(Fp.subtracted(low, high), Fp.subtracted(cross, Fp.added(low, high)))

    def squared: Fp2 = times(this)

    def scaled(factor: BigInt): Fp2 = Fp2(Fp.multiplied(real, factor), Fp.multiplied(imaginary, factor))

    /** The conjugate, which is also this element raised to the field's
      * characteristic.
      *
      * `p` is three modulo four here, so `i^p` is `-i` and the Frobenius
      * endomorphism of this extension is exactly the conjugation.
      */
    def conjugate: Fp2 = Fp2(real, Fp.negated(imaginary))

    def inverted: Fp2 =
      val norm = Fp.inverted(Fp.added(Fp.multiplied(real, real), Fp.multiplied(imaginary, imaginary)))
      Fp2(Fp.multiplied(real, norm), Fp.multiplied(Fp.negated(imaginary), norm))

    /** This element times the non-residue the two extensions above are built
      * over.
      */
    def timesNonResidue: Fp2 =
      Fp2(Fp.subtracted(Fp.multiplied(real, BigInt(9)), imaginary), Fp.added(real, Fp.multiplied(imaginary, BigInt(9))))

    def isZero: Boolean = real.signum == 0 && imaginary.signum == 0

  private object Fp2:
    val Zero: Fp2 = Fp2(BigInt(0), BigInt(0))
    val One: Fp2 = Fp2(BigInt(1), BigInt(0))

    def raised(base: Fp2, exponent: BigInt): Fp2 =
      var accumulator = One
      var square = base
      var remaining = exponent
      while remaining.signum > 0 do
        if remaining.testBit(0) then accumulator = accumulator.times(square)
        square = square.squared
        remaining = remaining >> 1
      accumulator

  /** An element `c0 + c1 * v + c2 * v^2` of the cubic extension in which `v^3`
    * is the non-residue.
    */
  final private case class Fp6(c0: Fp2, c1: Fp2, c2: Fp2):

    def plus(other: Fp6): Fp6 = Fp6(c0.plus(other.c0), c1.plus(other.c1), c2.plus(other.c2))

    def minus(other: Fp6): Fp6 = Fp6(c0.minus(other.c0), c1.minus(other.c1), c2.minus(other.c2))

    def negated: Fp6 = Fp6(c0.negated, c1.negated, c2.negated)

    /** Six multiplications rather than nine, each cross term recovered from a
      * product of sums.
      */
    def times(other: Fp6): Fp6 =
      val low = c0.times(other.c0)
      val mid = c1.times(other.c1)
      val high = c2.times(other.c2)
      Fp6(
        low.plus(c1.plus(c2).times(other.c1.plus(other.c2)).minus(mid).minus(high).timesNonResidue),
        c0.plus(c1).times(other.c0.plus(other.c1)).minus(low).minus(mid).plus(high.timesNonResidue),
        c0.plus(c2).times(other.c0.plus(other.c2)).minus(low).minus(high).plus(mid)
      )

    /** This element times `v`, which folds the top coefficient down through the
      * non-residue.
      */
    def timesV: Fp6 = Fp6(c2.timesNonResidue, c0, c1)

    def inverted: Fp6 =
      val a = c0.squared.minus(c1.times(c2).timesNonResidue)
      val b = c2.squared.timesNonResidue.minus(c0.times(c1))
      val c = c1.squared.minus(c0.times(c2))
      val factor = a.times(c0).plus(c.times(c1).timesNonResidue).plus(b.times(c2).timesNonResidue).inverted
      Fp6(a.times(factor), b.times(factor), c.times(factor))

  private object Fp6:
    val Zero: Fp6 = Fp6(Fp2.Zero, Fp2.Zero, Fp2.Zero)
    val One: Fp6 = Fp6(Fp2.One, Fp2.Zero, Fp2.Zero)

  /** An element `c0 + c1 * w` of the quadratic extension in which `w^2` is `v`.
    *
    * The pairing's values live here, and the two `F_p^2` coefficients spell six
    * of them by power of `w`: `w^2` is `v`, so `w^0`, `w^2` and `w^4` are `c0`'s
    * three and `w^1`, `w^3` and `w^5` are `c1`'s.
    */
  final private case class Fp12(c0: Fp6, c1: Fp6):

    def times(other: Fp12): Fp12 =
      val low = c0.times(other.c0)
      val high = c1.times(other.c1)
      Fp12(low.plus(high.timesV), c0.plus(c1).times(other.c0.plus(other.c1)).minus(low).minus(high))

    def squared: Fp12 = times(this)

    /** This element raised to `p^6`, which is the extension's own conjugation. */
    def conjugate: Fp12 = Fp12(c0, c1.negated)

    def inverted: Fp12 =
      val factor = c0.times(c0).minus(c1.times(c1).timesV).inverted
      Fp12(c0.times(factor), c1.times(factor).negated)

    def isOne: Boolean = this == Fp12.One

  private object Fp12:
    val One: Fp12 = Fp12(Fp6.One, Fp6.Zero)

    def raised(base: Fp12, exponent: BigInt): Fp12 =
      var accumulator = One
      var square = base
      var remaining = exponent
      while remaining.signum > 0 do
        if remaining.testBit(0) then accumulator = accumulator.times(square)
        square = square.squared
        remaining = remaining >> 1
      accumulator

  // ----------------------------------------------------- derived parameters

  /** The non-residue both extensions above are built over: `v^3` and `w^6`
    * alike.
    *
    * It is `9 + i` because the twist is, EIP-197 giving that curve as
    * `Y^2 = X^3 + 3/(i+9)`.
    */
  private val NonResidue: Fp2 = Fp2(BigInt(9), BigInt(1))

  /** The second group's curve coefficient, `3` over the non-residue. */
  private val TwistCoefficient: Fp2 = Fp2(BigInt(3), BigInt(0)).times(NonResidue.inverted)

  /** What `w` becomes under the Frobenius endomorphism, in the two forms the
    * pairing needs.
    *
    * `w^6` is the non-residue, so `w^(p-1)` is that raised to `(p-1)/6` -- an
    * element of the quadratic extension, computed here rather than tabulated.
    * The first and second thirds of that power carry a point of the twist to its
    * image, and the norm of the sixth carries a whole `F_p^12` element under the
    * SQUARED endomorphism, where each coefficient's own Frobenius is the
    * identity and only the power of `w` moves.
    */
  private val TwistFrobeniusX: Fp2 = Fp2.raised(NonResidue, (FieldModulus - 1) / 3)

  private val TwistFrobeniusY: Fp2 = Fp2.raised(NonResidue, (FieldModulus - 1) / 2)

  private val SquaredFrobenius: Fp2 =
    val sixth = Fp2.raised(NonResidue, (FieldModulus - 1) / 6)
    sixth.times(sixth.conjugate)

  // ------------------------------------------------------------- the points

  /** A point of the first group, `(0, 0)` standing for the point at infinity. */
  final private case class G1(x: BigInt, y: BigInt):
    def isInfinity: Boolean = x.signum == 0 && y.signum == 0

  /** A point of the second group, `(0, 0)` standing for the point at infinity. */
  final private case class G2(x: Fp2, y: Fp2):
    def isInfinity: Boolean = x.isZero && y.isZero
    def negated: G2 = G2(x, y.negated)

  private val G1Infinity: G1 = G1(BigInt(0), BigInt(0))

  private val G2Infinity: G2 = G2(Fp2.Zero, Fp2.Zero)

  // --------------------------------------------------------- the public work

  /** The sum of the two points `input` encodes, or nothing where it encodes no
    * such pair.
    *
    * A hundred and twenty-eight bytes are read; a shorter input is read as
    * though the bytes it does not have were zero, and anything past the
    * hundred and twenty-eighth is ignored. That is EIP-196's own rule and not a
    * convenience: *"if the input is shorter than expected, it is assumed to be
    * virtually padded with zeros at the end ... If the input is longer than
    * expected, surplus bytes at the end are ignored."*
    */
  def sum(input: IArray[Byte]): Option[IArray[Byte]] =
    for
      left <- pointAt(input, 0)
      right <- pointAt(input, G1Width)
    yield encoded(added(left, right))

  /** The first point `input` encodes scaled by the scalar behind it, or nothing
    * where it encodes no such point.
    *
    * The scalar is read as a whole word and is not constrained: *"The scalar can
    * be any number between `0` and `2**256-1`"*, so one at or above the group's
    * order is answered rather than refused.
    */
  def scaled(input: IArray[Byte]): Option[IArray[Byte]] =
    pointAt(input, 0).map(point => encoded(multiplied(point, wordAt(input, G1Width))))

  /** Whether the pairing product over the pairs `input` encodes is one, or
    * nothing where it encodes no such list.
    *
    * ==No input is padded here, unlike the two above==
    *
    * EIP-197 makes the length a rule rather than a convenience: *"If the input
    * length is not a multiple of `192`, the call fails."* An input of no bytes
    * is a whole number of pairs -- none -- and is the one case that answers true
    * without a pairing being computed: *"Empty input is valid and results in
    * returning one."*
    *
    * ==Both groups are checked for the curve and only the second for its
    * order==
    *
    * *"In order to check that an input is an element of `G_1`, verifying the
    * encoding of the coordinates and checking that they satisfy the curve
    * equation (or is the encoding of infinity) is sufficient. For `G_2`, in
    * addition to that, the order of the element has to be checked to be equal to
    * the group order."* The asymmetry is the first group's cofactor being one.
    */
  def pairingIsOne(input: IArray[Byte]): Option[Boolean] =
    if input.length % PairWidth != 0 then None
    else
      var accumulator = Fp12.One
      var offset = 0
      var refused = false
      while offset < input.length && !refused do
        val pair =
          for
            first <- pointAt(input, offset)
            second <- twistPointAt(input, offset + G1Width)
          yield millerLoop(first, second)
        pair match
          case Some(value) => accumulator = accumulator.times(value)
          case None        => refused = true
        offset += PairWidth
      if refused then None else Some(finalExponentiation(accumulator).isOne)

  // ------------------------------------------------------------- the codec

  /** The point the two words at `offset` encode, or nothing where they encode
    * none.
    *
    * A coordinate at or above the modulus is refused rather than reduced --
    * *"An encoding value of `p` or larger is invalid"* -- so a point that would
    * be valid had its coordinates been taken modulo `p` is not one this answers
    * for.
    *
    * ==The bound runs first and the two tests after it depend on that==
    *
    * Both the point at infinity and the curve equation are decided from the
    * coordinates AS THEY ARRIVED. That is only sound for coordinates already
    * known to be reduced: an encoding of exactly `p` compares unequal to the
    * zero it stands for, so moving the bound after either test would change
    * which rule refuses such an input, and moving it after BOTH would admit it.
    * The same ordering holds in [[twistPointAt]] for the same reason, where the
    * bound itself carries a residual this one does not.
    */
  private def pointAt(input: IArray[Byte], offset: Int): Option[G1] =
    val x = wordAt(input, offset)
    val y = wordAt(input, offset + WordWidth)
    if x >= FieldModulus || y >= FieldModulus then None
    else
      val point = G1(x, y)
      if point.isInfinity then Some(point)
      else if Fp.multiplied(y, y) != Fp.added(Fp.multiplied(Fp.multiplied(x, x), x), BigInt(3)) then None
      else Some(point)

  /** The twist point the four words at `offset` encode, or nothing where they
    * encode none or one outside the group.
    *
    * Each coefficient pair arrives with the multiple of `i` FIRST: EIP-197
    * encodes *"Elements `a * i + b` of `F_p^2` ... as two elements of `F_p`,
    * `(a, b)`"*. Reading the two the other way round produces a different point
    * that is on the curve about as often as the right one is not, so the
    * ordering is not one a curve check would report.
    *
    * ==The field bound is EQUIVALENT-MODULO-AN-OPEN-QUESTION, so no vector can
    * be written for it==
    *
    * Refusing components ABOVE the modulus rather than AT it would admit one
    * encoded as exactly `p`, which every operation below reduces to zero. So
    * the two spellings agree on every input but one: a point of the `q`-order
    * subgroup carrying a zero component, encoded with `p` in that component's
    * place. Whether this curve has such a point is open, and until it is
    * settled there is no input on which the two can be told apart.
    *
    * A zero whole COORDINATE is ruled out. The twist has order `q * (2p - q)`,
    * which is coprime to six, so it carries neither two- nor three-torsion --
    * which is what a point with `y` zero and a point with `x` zero
    * respectively would be. A zero COMPONENT
    * is a different condition and is not ruled out -- and no argument from this
    * family's shape can rule it out, because a small curve built by the same
    * recipe at `u = -3`, over this curve's own `9 + i`, has a `q`-order point
    * whose `x` is `168 + 0i`. An argument that closed the question here would
    * close it there and be false.
    *
    * **So this is a question about the curve rather than a coverage gap
    * awaiting a vector, and no effort should be spent writing one.** Deciding
    * it means enumerating a component across the whole field. The comparison
    * that ships is EIP-197's own text: *"An encoding value of `p` or larger is
    * invalid"*.
    */
  private def twistPointAt(input: IArray[Byte], offset: Int): Option[G2] =
    val coordinates = IArray.tabulate(4)(index => wordAt(input, offset + index * WordWidth))
    if coordinates.exists(_ >= FieldModulus) then None
    else
      val point = G2(Fp2(coordinates(1), coordinates(0)), Fp2(coordinates(3), coordinates(2)))
      if point.isInfinity then Some(point)
      else if point.y.squared != point.x.squared.times(point.x).plus(TwistCoefficient) then None
      else if !twistMultiplied(point, GroupOrder).isInfinity then None
      else Some(point)

  /** The whole word at `offset`, reading past the end of `input` as zeroes. */
  private def wordAt(input: IArray[Byte], offset: Int): BigInt =
    var value = BigInt(0)
    var index = 0
    while index < WordWidth do
      val position = offset + index
      val byte = if position < input.length then input(position) & 0xff else 0
      value = (value << 8) + byte
      index += 1
    value

  /** `point` as the two words it encodes, the point at infinity spelling
    * zeroes.
    */
  private def encoded(point: G1): IArray[Byte] =
    val out = new Array[Byte](G1Width)
    writeWord(out, 0, point.x)
    writeWord(out, WordWidth, point.y)
    IArray.unsafeFromArray(out)

  private def writeWord(out: Array[Byte], offset: Int, value: BigInt): Unit =
    val raw = value.toByteArray
    val taken = if raw.length < WordWidth then raw.length else WordWidth
    var index = 0
    while index < taken do
      out(offset + WordWidth - taken + index) = raw(raw.length - taken + index)
      index += 1

  // ------------------------------------------------------ the group law

  private def added(left: G1, right: G1): G1 =
    if left.isInfinity then right
    else if right.isInfinity then left
    else if left.x == right.x then
      // A point whose own negation it is doubles to infinity. That takes a zero
      // ordinate, which no point of a group of odd prime order has, so the
      // second half of this test cannot decide a case the first does not.
      if left.y == right.y && left.y.signum != 0 then doubled(left) else G1Infinity
    else
      val slope = Fp.multiplied(Fp.subtracted(right.y, left.y), Fp.inverted(Fp.subtracted(right.x, left.x)))
      chord(slope, left, right.x)

  private def doubled(point: G1): G1 =
    val slope = Fp.multiplied(
      Fp.multiplied(BigInt(3), Fp.multiplied(point.x, point.x)),
      Fp.inverted(Fp.added(point.y, point.y))
    )
    chord(slope, point, point.x)

  private def chord(slope: BigInt, from: G1, otherX: BigInt): G1 =
    val x = Fp.subtracted(Fp.subtracted(Fp.multiplied(slope, slope), from.x), otherX)
    G1(x, Fp.subtracted(Fp.multiplied(slope, Fp.subtracted(from.x, x)), from.y))

  private def multiplied(point: G1, scalar: BigInt): G1 =
    var accumulator = G1Infinity
    var addend = point
    var remaining = scalar
    while remaining.signum > 0 do
      if remaining.testBit(0) then accumulator = added(accumulator, addend)
      addend = added(addend, addend)
      remaining = remaining >> 1
    accumulator

  private def twistAdded(left: G2, right: G2): G2 =
    if left.isInfinity then right
    else if right.isInfinity then left
    else if left.x == right.x then if left.y == right.y && !left.y.isZero then twistDoubled(left) else G2Infinity
    else twistChord(twistSlope(left, right), left, right.x)

  private def twistDoubled(point: G2): G2 = twistChord(twistTangent(point), point, point.x)

  private def twistSlope(left: G2, right: G2): Fp2 =
    right.y.minus(left.y).times(right.x.minus(left.x).inverted)

  private def twistTangent(point: G2): Fp2 =
    point.x.squared.scaled(BigInt(3)).times(point.y.plus(point.y).inverted)

  private def twistChord(slope: Fp2, from: G2, otherX: Fp2): G2 =
    val x = slope.squared.minus(from.x).minus(otherX)
    G2(x, slope.times(from.x.minus(x)).minus(from.y))

  private def twistMultiplied(point: G2, scalar: BigInt): G2 =
    var accumulator = G2Infinity
    var addend = point
    var remaining = scalar
    while remaining.signum > 0 do
      if remaining.testBit(0) then accumulator = twistAdded(accumulator, addend)
      addend = twistAdded(addend, addend)
      remaining = remaining >> 1
    accumulator

  // --------------------------------------------------------- the pairing

  /** The image of a twist point under the Frobenius endomorphism, which on this
    * group is multiplication by the field's characteristic.
    */
  private def twistFrobenius(point: G2): G2 =
    G2(TwistFrobeniusX.times(point.x.conjugate), TwistFrobeniusY.times(point.y.conjugate))

  /** The line through two twist points, evaluated at a point of the first
    * group.
    *
    * Untwisting carries `(x, y)` to `(x * w^2, y * w^3)`, so a line whose slope
    * is `s` in the quadratic extension has slope `s * w` in the twelfth, and its
    * value at `(a, b)` is `b - s * a * w + (s * x - y) * w^3`. Three of the six
    * coefficients are therefore zero, which is why this is built rather than
    * multiplied out.
    */
  private def line(slope: Fp2, from: G2, at: G1): Fp12 =
    Fp12(
      Fp6(Fp2(at.y, BigInt(0)), Fp2.Zero, Fp2.Zero),
      Fp6(slope.scaled(at.x).negated, slope.times(from.x).minus(from.y), Fp2.Zero)
    )

  /** The Miller function of `second` evaluated at `first`, before the final
    * exponentiation.
    *
    * The loop counts down from the second-highest bit of [[LoopCount]]. The two
    * steps after it are what makes this the optimal ate pairing rather than the
    * ate pairing: the loop alone leaves a value that is not bilinear, and the
    * lines through the Frobenius image and the negated squared Frobenius image
    * are what complete it.
    *
    * Either point being at infinity answers one, which is the identity the
    * caller multiplies into its accumulator -- so a pair with an infinity in it
    * contributes nothing to the product rather than being skipped by the caller.
    */
  private def millerLoop(first: G1, second: G2): Fp12 =
    if first.isInfinity || second.isInfinity then Fp12.One
    else
      var value = Fp12.One
      var running = second
      var bit = LoopCount.bitLength - 2
      while bit >= 0 do
        val tangent = twistTangent(running)
        value = value.squared.times(line(tangent, running, first))
        running = twistChord(tangent, running, running.x)
        if LoopCount.testBit(bit) then
          val slope = twistSlope(running, second)
          value = value.times(line(slope, running, first))
          running = twistChord(slope, running, second.x)
        bit -= 1
      val image = twistFrobenius(second)
      val squaredImage = twistFrobenius(image).negated
      val toImage = twistSlope(running, image)
      value = value.times(line(toImage, running, first))
      running = twistChord(toImage, running, image.x)
      value.times(line(twistSlope(running, squaredImage), running, first))

  /** A Miller value raised to `(p^12 - 1) / q`.
    *
    * The two cheap factors go first. Raising to `p^6 - 1` is one conjugation
    * against one inversion, the conjugation BEING the sixth Frobenius power
    * here. Raising to `p^2 + 1` is one application of the squared Frobenius
    * against the value itself. What is left is [[HardExponent]].
    */
  private def finalExponentiation(value: Fp12): Fp12 =
    val eased = value.conjugate.times(value.inverted)
    Fp12.raised(squaredFrobenius(eased).times(eased), HardExponent)

  /** A twelfth-extension element raised to `p^2`.
    *
    * Every coefficient is fixed by it -- squaring the Frobenius is the identity
    * on the quadratic extension -- so all that moves is the power of `w` each
    * one carries, and each is scaled by [[SquaredFrobenius]] raised to that
    * power.
    */
  private def squaredFrobenius(value: Fp12): Fp12 =
    var scale = Fp2.One
    val scales = Array.fill(6)(Fp2.One)
    var index = 0
    while index < 6 do
      scales(index) = scale
      scale = scale.times(SquaredFrobenius)
      index += 1
    Fp12(
      Fp6(value.c0.c0.times(scales(0)), value.c0.c1.times(scales(2)), value.c0.c2.times(scales(4))),
      Fp6(value.c1.c0.times(scales(1)), value.c1.c1.times(scales(3)), value.c1.c2.times(scales(5)))
    )
