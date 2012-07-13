package rocket

import Chisel._
import Node._
import Constants._

class BigMem[T <: Data](n: Int, readLatency: Int, leaf: Mem[Bits])(gen: => T) extends Component
{
  val io = new Bundle {
    val addr = UFix(INPUT, log2Up(n))
    val en = Bool(INPUT)
    val rw = Bool(INPUT)
    val wdata = gen.asInput
    val wmask = gen.asInput
    val rdata = gen.asOutput
  }
  require(readLatency >= 0 && readLatency <= 2)
  val data = gen
  val colMux = if (2*data.width <= leaf.data.width && n > leaf.n) 1 << math.floor(math.log(leaf.data.width/data.width)/math.log(2)).toInt else 1
  val nWide = if (data.width > leaf.data.width) 1+(data.width-1)/leaf.data.width else 1
  val nDeep = if (n > colMux*leaf.n) 1+(n-1)/(colMux*leaf.n) else 1
  if (nDeep > 1 || colMux > 1)
    require(isPow2(n) && isPow2(leaf.n))

  val idx = io.addr(log2Up(n/nDeep/colMux)-1, 0)
  val rdataDeep = Vec(nDeep) { Bits() }
  val rdataSel = Vec(nDeep) { Bool() }
  val cond = Vec(nDeep) { Bool() }
  val ren = Vec(nDeep) { Bool() }
  val reg_ren = Vec(nDeep) { Reg() { Bool() } }
  val reg2_ren = Vec(nDeep) { Reg() { Bool() } }
  val reg_raddr = Vec(nDeep) { Reg() { UFix() } }
  val reg2_raddr = Vec(nDeep) { Reg() { UFix() } }
  val renOut = Vec(nDeep) { Bool() }
  val raddrOut = Vec(nDeep) { UFix() }
  val rdata = Vec(nDeep) { Vec(nWide) { Bits() } }
  val wdata = io.wdata.toBits
  val wmask = io.wmask.toBits
  for (i <- 0 until nDeep) {
    cond(i) := (if (nDeep == 1) io.en else io.en && UFix(i) === io.addr(log2Up(n)-1, log2Up(n/nDeep)))
    ren(i) := cond(i) && !io.rw
    reg_ren(i) := ren(i)
    reg2_ren(i) := reg_ren(i)
    when (ren(i)) { reg_raddr(i) := io.addr }
    when (reg_ren(i)) { reg2_raddr(i) := reg_raddr(i) }
    renOut(i) := (if (readLatency > 1) reg2_ren(i) else if (readLatency > 0) reg_ren(i) else ren(i))
    raddrOut(i) := (if (readLatency > 1) reg2_raddr(i) else if (readLatency > 0) reg_raddr(i) else io.addr)

    for (j <- 0 until nWide) {
      val mem = leaf.clone
      var dout: Bits = null
      val dout1 = if (readLatency > 0) Reg() { Bits() } else null

      var wmask0 = Fill(colMux, wmask(math.min(wmask.getWidth, leaf.data.width*(j+1))-1, leaf.data.width*j))
      if (colMux > 1)
        wmask0 = wmask0 & FillInterleaved(gen.width, UFixToOH(io.addr(log2Up(n/nDeep)-1, log2Up(n/nDeep/colMux)), log2Up(colMux)))
      val wdata0 = Fill(colMux, wdata(math.min(wdata.getWidth, leaf.data.width*(j+1))-1, leaf.data.width*j))
      when (cond(i)) {
        when (io.rw) { mem.write(idx, wdata0, wmask0) }
        .otherwise { if (readLatency > 0) dout1 := mem(idx) }
      }

      if (readLatency == 0) {
        dout = mem(idx)
      } else if (readLatency == 1) {
        dout = dout1
      } else {
        val dout2 = Reg() { Bits() }
        when (reg_ren(i)) { dout2 := dout1 }
        dout = dout2
      }

      rdata(i)(j) := dout
    }
    val rdataWide = rdata(i).reduceLeft((x, y) => Cat(y, x))

    var colMuxOut = rdataWide
    if (colMux > 1) {
      val colMuxIn = Vec((0 until colMux).map(k => rdataWide(gen.width*(k+1)-1, gen.width*k))) { Bits() }
      colMuxOut = colMuxIn(raddrOut(i)(log2Up(n/nDeep)-1, log2Up(n/nDeep/colMux)))
    }

    rdataDeep(i) := colMuxOut
    rdataSel(i) := renOut(i)
  }

  io.rdata := Mux1H(rdataSel, rdataDeep)
}

class LLCDataReq(ways: Int) extends MemReqCmd
{
  val way = UFix(width = log2Up(ways))
  val isWriteback = Bool()

  override def clone = new LLCDataReq(ways).asInstanceOf[this.type]
}

class LLCMSHRFile(sets: Int, ways: Int, outstanding: Int) extends Component
{
  val io = new Bundle {
    val cpu = (new FIFOIO) { new MemReqCmd }.flip
    val repl_way = UFix(INPUT, log2Up(ways))
    val repl_dirty = Bool(INPUT)
    val repl_tag = UFix(INPUT, PADDR_BITS - OFFSET_BITS - log2Up(sets))
    val data = (new FIFOIO) { new LLCDataReq(ways) }
    val tag = (new FIFOIO) { new Bundle {
      val addr = UFix(width = PADDR_BITS - OFFSET_BITS)
      val way = UFix(width = log2Up(ways))
    } }
    val mem = new ioMem
    val mem_resp_set = UFix(OUTPUT, log2Up(sets))
    val mem_resp_way = UFix(OUTPUT, log2Up(ways))
  }

  class MSHR extends Bundle {
    val addr = UFix(width = PADDR_BITS - OFFSET_BITS)
    val way = UFix(width = log2Up(ways))
    val tag = io.cpu.bits.tag.clone
    val refilled = Bool()
    val refillCount = UFix(width = log2Up(REFILL_CYCLES))
    val requested = Bool()
    val old_dirty = Bool()
    val old_tag = UFix(width = PADDR_BITS - OFFSET_BITS - log2Up(sets))

    override def clone = new MSHR().asInstanceOf[this.type]
  }

  val valid = Vec(outstanding) { Reg(resetVal = Bool(false)) }
  val validBits = valid.toBits
  val freeId = PriorityEncoder(~validBits)
  val mshr = Vec(outstanding) { Reg() { new MSHR } }
  when (io.cpu.valid && io.cpu.ready) {
    valid(freeId) := Bool(true)
    mshr(freeId).addr := io.cpu.bits.addr
    mshr(freeId).tag := io.cpu.bits.tag
    mshr(freeId).way := io.repl_way
    mshr(freeId).old_dirty := io.repl_dirty
    mshr(freeId).old_tag := io.repl_tag
    mshr(freeId).requested := Bool(false)
    mshr(freeId).refillCount := UFix(0)
    mshr(freeId).refilled := Bool(false)
  }

  val requests = Cat(Bits(0), (outstanding-1 to 0 by -1).map(i => valid(i) && !mshr(i).old_dirty && !mshr(i).requested):_*)
  val request = requests.orR
  val requestId = PriorityEncoder(requests)
  when (io.mem.req_cmd.valid && io.mem.req_cmd.ready) { mshr(requestId).requested := Bool(true) }

  val refillId = io.mem.resp.bits.tag(log2Up(outstanding)-1, 0)
  val refillCount = mshr(refillId).refillCount
  when (io.mem.resp.valid) {
    mshr(refillId).refillCount := refillCount + UFix(1)
    when (refillCount === UFix(REFILL_CYCLES-1)) { mshr(refillId).refilled := Bool(true) }
  }

  val replays = Cat(Bits(0), (outstanding-1 to 0 by -1).map(i => valid(i) && mshr(i).refilled):_*)
  val replay = replays.orR
  val replayId = PriorityEncoder(replays)
  when (replay && io.data.ready && io.tag.ready) { valid(replayId) := Bool(false) }

  val writebacks = Cat(Bits(0), (outstanding-1 to 0 by -1).map(i => valid(i) && mshr(i).old_dirty):_*)
  val writeback = writebacks.orR
  val writebackId = PriorityEncoder(writebacks)
  when (writeback && io.data.ready && !replay) { mshr(writebackId).old_dirty := Bool(false) }

  val conflicts = Cat(Bits(0), (0 until outstanding).map(i => valid(i) && io.cpu.bits.addr(log2Up(sets)-1, 0) === mshr(i).addr(log2Up(sets)-1, 0)):_*)
  io.cpu.ready := !conflicts.orR && !validBits.andR

  io.data.valid := replay && io.tag.ready || writeback
  io.data.bits.rw := Bool(false)
  io.data.bits.tag := mshr(replayId).tag
  io.data.bits.isWriteback := Bool(true)
  io.data.bits.addr := Cat(mshr(writebackId).old_tag, mshr(writebackId).addr(log2Up(sets)-1, 0)).toUFix
  io.data.bits.way := mshr(writebackId).way
  when (replay) {
    io.data.bits.isWriteback := Bool(false)
    io.data.bits.addr := mshr(replayId).addr
    io.data.bits.way := mshr(replayId).way
  }
  io.tag.valid := replay && io.data.ready
  io.tag.bits.addr := io.data.bits.addr
  io.tag.bits.way := io.data.bits.way

  io.mem.req_cmd.valid := request
  io.mem.req_cmd.bits.rw := Bool(false)
  io.mem.req_cmd.bits.addr := mshr(requestId).addr
  io.mem.req_cmd.bits.tag := requestId
  io.mem_resp_set := mshr(refillId).addr
  io.mem_resp_way := mshr(refillId).way
}

class LLCWriteback(requestors: Int) extends Component
{
  val io = new Bundle {
    val req = Vec(requestors) { (new FIFOIO) { UFix(width = PADDR_BITS - OFFSET_BITS) }.flip }
    val data = Vec(requestors) { (new FIFOIO) { new MemData }.flip }
    val mem = new ioMem
  }

  val valid = Reg(resetVal = Bool(false))
  val who = Reg() { UFix() }
  val addr = Reg() { UFix() }
  val cmd_sent = Reg() { Bool() }
  val data_sent = Reg() { Bool() }
  val count = Reg(resetVal = UFix(0, log2Up(REFILL_CYCLES)))

  var anyReq = Bool(false)
  for (i <- 0 until requestors) {
    io.req(i).ready := !valid && !anyReq
    io.data(i).ready := valid && who === UFix(i) && io.mem.req_data.ready
    anyReq = anyReq || io.req(i).valid
  }

  val nextWho = PriorityEncoder(io.req.map(_.valid))
  when (!valid && io.req.map(_.valid).reduceLeft(_||_)) {
    valid := Bool(true)
    cmd_sent := Bool(false)
    data_sent := Bool(false)
    who := nextWho
    addr := io.req(nextWho).bits
  }

  when (io.mem.req_data.valid && io.mem.req_data.ready) {
    count := count + UFix(1)
    when (count === UFix(REFILL_CYCLES-1)) {
      data_sent := Bool(true)
      when (cmd_sent) { valid := Bool(false) }
    }
  }
  when (io.mem.req_cmd.valid && io.mem.req_cmd.ready) { cmd_sent := Bool(true) }
  when (valid && cmd_sent && data_sent) { valid := Bool(false) }

  io.mem.req_cmd.valid := valid && !cmd_sent
  io.mem.req_cmd.bits.addr := addr
  io.mem.req_cmd.bits.rw := Bool(true)

  io.mem.req_data.valid := valid && !data_sent && io.data(who).valid
  io.mem.req_data.bits := io.data(who).bits
}

class LLCData(sets: Int, ways: Int, leaf: Mem[Bits]) extends Component
{
  val io = new Bundle {
    val req = (new FIFOIO) { new LLCDataReq(ways) }.flip
    val req_data = (new FIFOIO) { new MemData }.flip
    val writeback = (new FIFOIO) { UFix(width = PADDR_BITS - OFFSET_BITS) }
    val writeback_data = (new FIFOIO) { new MemData }
    val resp = (new PipeIO) { new MemResp }
    val mem_resp = (new PipeIO) { new MemResp }.flip
    val mem_resp_set = UFix(INPUT, log2Up(sets))
    val mem_resp_way = UFix(INPUT, log2Up(ways))
  }

  val data = new BigMem(sets*ways*REFILL_CYCLES, 2, leaf)(Bits(width = MEM_DATA_BITS))
  class QEntry extends MemResp {
    val isWriteback = Bool()
    override def clone = new QEntry().asInstanceOf[this.type]
  }
  val q = (new queue(4)) { new QEntry }
  val qReady = q.io.count <= UFix(q.entries - 3)
  val valid = Reg(resetVal = Bool(false))
  val req = Reg() { io.req.bits.clone }
  val count = Reg(resetVal = UFix(0, log2Up(REFILL_CYCLES)))
  val refillCount = Reg(resetVal = UFix(0, log2Up(REFILL_CYCLES)))

  when (data.io.en && !io.mem_resp.valid) {
    count := count + UFix(1)
    when (valid && count === UFix(REFILL_CYCLES-1)) { valid := Bool(false) }
  }
  when (io.req.valid && io.req.ready) { valid := Bool(true); req := io.req.bits }
  when (io.mem_resp.valid) { refillCount := refillCount + UFix(1) }

  data.io.en := io.req.valid && io.req.ready && Mux(io.req.bits.rw, io.req_data.valid, qReady)
  data.io.addr := Cat(io.req.bits.way, io.req.bits.addr(log2Up(sets)-1, 0), count).toUFix
  data.io.rw := io.req.bits.rw
  data.io.wdata := io.req_data.bits.data
  data.io.wmask := Fix(-1, io.req_data.bits.data.width)
  when (valid) {
    data.io.en := Mux(req.rw, io.req_data.valid, qReady)
    data.io.addr := Cat(req.way, req.addr(log2Up(sets)-1, 0), count).toUFix
    data.io.rw := req.rw
  }
  when (io.mem_resp.valid) {
    data.io.en := Bool(true)
    data.io.addr := Cat(io.mem_resp_way, io.mem_resp_set, refillCount).toUFix
    data.io.rw := Bool(true)
    data.io.wdata := io.mem_resp.bits.data
  }

  q.io.enq.valid := Reg(Reg(data.io.en && !data.io.rw, resetVal = Bool(false)), resetVal = Bool(false))
  q.io.enq.bits.tag := Reg(Reg(Mux(valid, req.tag, io.req.bits.tag)))
  q.io.enq.bits.data := data.io.rdata
  q.io.enq.bits.isWriteback := Reg(Reg(Mux(valid, req.isWriteback, io.req.bits.isWriteback)))

  io.req.ready := !valid && Mux(io.req.bits.isWriteback, io.writeback.ready, Bool(true))
  io.req_data.ready := !io.mem_resp.valid && Mux(valid, req.rw, io.req.valid && io.req.bits.rw)

  io.writeback.valid := io.req.valid && io.req.ready && io.req.bits.isWriteback
  io.writeback.bits := io.req.bits.addr

  q.io.deq.ready := Mux(q.io.deq.bits.isWriteback, io.writeback_data.ready, Bool(true))
  io.resp.valid := q.io.deq.valid && !q.io.deq.bits.isWriteback
  io.resp.bits := q.io.deq.bits
  io.writeback_data.valid := q.io.deq.valid && q.io.deq.bits.isWriteback
  io.writeback_data.bits := q.io.deq.bits
}

class DRAMSideLLC(sets: Int, ways: Int, outstanding: Int, tagLeaf: Mem[Bits], dataLeaf: Mem[Bits]) extends Component
{
  val io = new Bundle {
    val cpu = new ioMem().flip
    val mem = new ioMem
  }

  val tagWidth = PADDR_BITS - OFFSET_BITS - log2Up(sets)
  val metaWidth = tagWidth + 2 // valid + dirty

  val memCmdArb = (new Arbiter(2)) { new MemReqCmd }
  val dataArb = (new Arbiter(2)) { new LLCDataReq(ways) }
  val mshr = new LLCMSHRFile(sets, ways, outstanding)
  val tags = new BigMem(sets, 1, tagLeaf)(Bits(width = metaWidth*ways))
  val data = new LLCData(sets, ways, dataLeaf)
  val writeback = new LLCWriteback(2)

  val initCount = Reg(resetVal = UFix(0, log2Up(sets+1)))
  val initialize = !initCount(log2Up(sets))
  when (initialize) { initCount := initCount + UFix(1) }

  val stall_s1 = Bool()
  val replay_s1 = Reg(resetVal = Bool(false))
  val s1_valid = Reg(io.cpu.req_cmd.valid && !stall_s1 || replay_s1, resetVal = Bool(false))
  replay_s1 := s1_valid && stall_s1
  val s1 = Reg() { new MemReqCmd }
  when (io.cpu.req_cmd.valid && io.cpu.req_cmd.ready) { s1 := io.cpu.req_cmd.bits }

  val stall_s2 = Bool()
  val s2_valid = Reg(resetVal = Bool(false))
  s2_valid := s1_valid && !replay_s1 && !stall_s1 || stall_s2
  val s2 = Reg() { new MemReqCmd }
  val s2_tags = Vec(ways) { Reg() { Bits(width = metaWidth) } }
  when (s1_valid && !stall_s1 && !replay_s1) {
    s2 := s1
    for (i <- 0 until ways)
      s2_tags(i) := tags.io.rdata(metaWidth*(i+1)-1, metaWidth*i)
  }
  val s2_hits = s2_tags.map(t => t(tagWidth) && s2.addr(s2.addr.width-1, s2.addr.width-tagWidth) === t(tagWidth-1, 0))
  val s2_hit_way = OHToUFix(s2_hits)
  val s2_hit = s2_hits.reduceLeft(_||_)
  val s2_hit_dirty = s2_tags(s2_hit_way)(tagWidth+1)
  val repl_way = LFSR16(s2_valid)(log2Up(ways)-1, 0)
  val repl_tag = s2_tags(repl_way).toUFix
  val setDirty = s2_valid && s2.rw && s2_hit && !s2_hit_dirty
  stall_s1 := initialize || mshr.io.tag.valid || setDirty || s2_valid && !s2_hit || stall_s2

  tags.io.en := (io.cpu.req_cmd.valid || replay_s1) && !stall_s1 || initialize || setDirty || mshr.io.tag.valid
  tags.io.addr := Mux(initialize, initCount, Mux(setDirty, s2.addr, Mux(mshr.io.tag.valid, mshr.io.tag.bits.addr, Mux(replay_s1, s1.addr, io.cpu.req_cmd.bits.addr)))(log2Up(sets)-1,0))
  tags.io.rw := initialize || setDirty || mshr.io.tag.valid
  tags.io.wdata := Mux(initialize, UFix(0), Fill(ways, Cat(setDirty, Bool(true), Mux(setDirty, s2.addr, mshr.io.tag.bits.addr)(mshr.io.tag.bits.addr.width-1, mshr.io.tag.bits.addr.width-tagWidth))))
  tags.io.wmask := FillInterleaved(metaWidth, Mux(initialize, Fix(-1, ways), UFixToOH(Mux(setDirty, s2_hit_way, mshr.io.tag.bits.way))))

  mshr.io.cpu.valid := s2_valid && !s2_hit && !s2.rw
  mshr.io.cpu.bits := s2
  mshr.io.repl_way := repl_way
  mshr.io.repl_dirty := repl_tag(tagWidth+1, tagWidth).andR
  mshr.io.repl_tag := repl_tag
  mshr.io.mem.resp := io.mem.resp
  mshr.io.tag.ready := !setDirty

  data.io.req <> dataArb.io.out
  data.io.mem_resp := io.mem.resp
  data.io.mem_resp_set := mshr.io.mem_resp_set
  data.io.mem_resp_way := mshr.io.mem_resp_way
  data.io.req_data.bits := io.cpu.req_data.bits

  writeback.io.req(0) <> data.io.writeback
  writeback.io.data(0) <> data.io.writeback_data
  writeback.io.req(1).valid := s2_valid && !s2_hit && s2.rw
  writeback.io.req(1).bits := s2.addr
  writeback.io.data(1).valid := io.cpu.req_data.valid
  writeback.io.data(1).bits := io.cpu.req_data.bits
  data.io.req_data.valid := io.cpu.req_data.valid && !writeback.io.data(1).ready

  memCmdArb.io.in(0) <> mshr.io.mem.req_cmd
  memCmdArb.io.in(1) <> writeback.io.mem.req_cmd

  dataArb.io.in(0) <> mshr.io.data
  dataArb.io.in(1).valid := s2_valid && s2_hit
  dataArb.io.in(1).bits := s2
  dataArb.io.in(1).bits.way := s2_hit_way
  dataArb.io.in(1).bits.isWriteback := Bool(false)

  stall_s2 := s2_valid && !Mux(s2_hit, dataArb.io.in(1).ready, Mux(s2.rw, writeback.io.req(1).ready, mshr.io.cpu.ready))

  io.cpu.resp <> data.io.resp
  io.cpu.req_cmd.ready := !stall_s1 && !replay_s1
  io.cpu.req_data.ready := writeback.io.data(1).ready || data.io.req_data.ready
  io.mem.req_cmd <> memCmdArb.io.out
  io.mem.req_data <> writeback.io.mem.req_data
}
