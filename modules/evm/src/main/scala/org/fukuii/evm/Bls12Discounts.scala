package org.fukuii.evm

/** EIP-2537's multi-exponentiation discount tables, as the specification states
  * them.
  *
  * ==Why a table and not a formula==
  *
  * The document prices a multi-exponentiation of `k` pairs at `k` times the
  * single-multiplication price, scaled by a discount that falls as `k` rises and
  * then flattens. The discount is not derived from anything -- it is 128
  * measured values per group, chosen by the proposal, with everything past the
  * table held at the last one. So it is transcribed rather than computed, and
  * transcribed from the executable specification rather than from the proposal's
  * prose, because that is what the published vectors are filled from.
  *
  * `ethereum/execution-specs` @ `0cc100eb1`,
  * `src/ethereum/forks/prague/vm/precompiled_contracts/bls12_381/__init__.py`.
  *
  * **The index is `k - 1`, not `k`.** A multi-exponentiation of one pair reads
  * the first entry. An implementation indexing by `k` is one entry out at every
  * size and still produces a plausible number at every size -- which is what
  * makes this worth stating rather than leaving to the reader.
  */
object Bls12Discounts:

  /** The divisor the discount is applied through. */
  val Multiplier: BigInt = BigInt(1000)

  /** What every size past the table is discounted by, for the first group. */
  val G1Max: BigInt = BigInt(519)

  /** What every size past the table is discounted by, for the second group. */
  val G2Max: BigInt = BigInt(524)

  /** The first group's 128 entries, `k - 1` indexed. */
  val G1: Vector[Int] =
    Vector(
      1000, 949, 848, 797, 764, 750, 738, 728, 719, 712, 705, 698, 692, 687, 682, 677, 673, 669, 665, 661, 658, 654,
      651, 648, 645, 642, 640, 637, 635, 632, 630, 627, 625, 623, 621, 619, 617, 615, 613, 611, 609, 608, 606, 604, 603,
      601, 599, 598, 596, 595, 593, 592, 591, 589, 588, 586, 585, 584, 582, 581, 580, 579, 577, 576, 575, 574, 573, 572,
      570, 569, 568, 567, 566, 565, 564, 563, 562, 561, 560, 559, 558, 557, 556, 555, 554, 553, 552, 551, 550, 549, 548,
      547, 547, 546, 545, 544, 543, 542, 541, 540, 540, 539, 538, 537, 536, 536, 535, 534, 533, 532, 532, 531, 530, 529,
      528, 528, 527, 526, 525, 525, 524, 523, 522, 522, 521, 520, 520, 519
    )

  /** The second group's 128 entries, `k - 1` indexed. */
  val G2: Vector[Int] =
    Vector(
      1000, 1000, 923, 884, 855, 832, 812, 796, 782, 770, 759, 749, 740, 732, 724, 717, 711, 704, 699, 693, 688, 683,
      679, 674, 670, 666, 663, 659, 655, 652, 649, 646, 643, 640, 637, 634, 632, 629, 627, 624, 622, 620, 618, 615, 613,
      611, 609, 607, 606, 604, 602, 600, 598, 597, 595, 593, 592, 590, 589, 587, 586, 584, 583, 582, 580, 579, 578, 576,
      575, 574, 573, 571, 570, 569, 568, 567, 566, 565, 563, 562, 561, 560, 559, 558, 557, 556, 555, 554, 553, 552, 552,
      551, 550, 549, 548, 547, 546, 545, 545, 544, 543, 542, 541, 541, 540, 539, 538, 537, 537, 536, 535, 535, 534, 533,
      532, 532, 531, 530, 530, 529, 528, 528, 527, 526, 526, 525, 524, 524
    )

  /** The discount for `k` pairs in the first group, flattening past the table. */
  def g1For(k: Int): BigInt = at(G1, k, G1Max)

  /** The discount for `k` pairs in the second group, flattening past the table. */
  def g2For(k: Int): BigInt = at(G2, k, G2Max)

  private def at(table: Vector[Int], k: Int, beyond: BigInt): BigInt =
    if k <= 0 then BigInt(0)
    else if k > table.length then beyond
    else BigInt(table(k - 1))
