
PmodEnv : Pattern {
	classvar watchdogEnabled = true;
	var valPat, timePat, curvePat, repeats;

	*new { arg valPat, timePat, curvePat, repeats=1;
		^super.newCopyArgs(valPat, timePat, curvePat, repeats);
	}

	*initClass {
		SynthDef(\PmodEnv_mono, { arg out=0, gate=1;
			var sig;
			sig = EnvGen.kr(\env.kr(Env([1,1],[0.1])), \itrig.tr(1), doneAction:0);
			sig = sig * EnvGen.kr(Env([1,1,1],[0.1,0.8], releaseNode:1), gate, doneAction:2);
			Out.kr(out, sig);
		}).add;
	}

	storeArgs { ^[valPat, timePat, curvePat, repeats] }

	embedInStream { arg strev;
		^Prout({ arg ev;

			var timestr;
			var running = true;
			var cleanup = EventStreamCleanup.new;
			var cleanup_fun;
			var patplayer;
			var finish_fun;
			var cmdperiod_fun;
			var watchdog;
			var bus;
			var watchdogEnabled = this.class.watchdogEnabled;

			cleanup_fun = {
				//[valPat, bus].debug("pmodenv: CLEANUP");
				patplayer.stop;
				running = false;
				{
					2.wait;
					//CmdPeriod.objects.do { arg obj, idx;
					////idx.debug("cmdperiod obj");
					//if(obj.isKindOf(Function)) {
					////obj.dump
					//};
					//};
					//cmdperiod_fun.dump;
					//CmdPeriod.remove(cmdperiod_fun);
					//CmdPeriod.objects.debug("cmdperiod after");
					if(bus.index.notNil) {
						bus.free;
					}
				}.fork;
			};
			cleanup.addFunction(ev, cleanup_fun); // should not be inside \finish

			watchdog = (
				alive: { arg self, dur;
					//dur.debug("alive");
					self.aliveDur = dur * 2; // times 2 for security
					self.aliveTime = thisThread.clock.beats;
				},

				isDead: { arg self;
					//[self.aliveTime, self.aliveDur].debug("isDead");
					if(watchdogEnabled == true) {
						if(self.aliveTime.notNil and: {self.aliveDur.notNil}) {
							if(thisThread.clock.beats - self.aliveTime > self.aliveDur ) {
								true
							} {
								false
							}
						} {
							false
						};
					} {
						false
					};
				},
			);

			ev = PnoteEnv.makePayload(ev, { arg iev;

				bus = Bus.control(Server.default, 1);

				//[valPat, bus].debug("pmodenv: NEW PAT");

				//if(timePat == \dur) {
					//timePat = Pfunc({ arg iev; ev.delta.value });
				//} {
					//timePat = timePat ??  1;
				//};
				timePat = timePat ?? { Pfunc({ ev.delta.value }) };
				curvePat = curvePat ??  { 0 };


				cmdperiod_fun = { 
					//[ valPat, bus ].debug("cmdperiod: free bus");
					if(bus.index.notNil) {
						bus.free 
					}
				};
				CmdPeriod.doOnce(cmdperiod_fun);

				watchdog.alive(iev.delta ?? iev.dur);

				patplayer = Pmono(\PmodEnv_mono,
					\out, bus,
					\itrig, 1,
					[ \dur, \env ], Prout({ arg monoev;
						var valstr = valPat.asStream;
						var curvestr = curvePat.asStream;
						var previous = valstr.next;
						var time;
						timestr = timePat.asStream;

						block { arg break;
							valstr.do({ arg val;
								var prev = previous;
								var curve;
								//val.debug("pmodenv val");
								//ev.debug("ev inside modenvmono");
								time = timestr.next;
								curve = curvestr.next;
								if(time.isNil) { 
									time = 2;
									monoev[\dur] = time;
									break.value;
								};
								if(curve.isNil) { 
									time = 2;
									monoev[\dur] = time;
									break.value;
								};

								//monoev[\dur] = time;
								//Env([prev,val],[time]/thisThread.clock.tempo).asCompileString.debug("env");
								if(watchdog.isDead) {
									//"dead!".debug;
									break.value;
								};
								monoev = [time, [ Env([prev,val],[time]/thisThread.clock.tempo, curve) ]].yield;

								previous = val;
							});
						};
						{
							cmdperiod_fun.(); // cleanup only bus since other are already cleaned
						}.defer(2);
						running = false;
						monoev;
					}),
					\legato, 1,
				).play;
				bus.asMap;
			});


			while{ running == true } {
				ev = PnoteEnv.makePayload(ev, { arg iev;
					watchdog.alive(iev.delta ?? iev.dur);
					cleanup.update(iev);
					bus.asMap
				});
				//ev = bus.asMap.yield;
			};
			//[valPat.asCompileString, bus].debug("pmodenv: cleanup");
			cleanup_fun.();
			//cleanup.exit(ev); // this cleanup all PmodEnv of the pattern
			ev;
		}).repeat(repeats.clip(1,inf)).embedInStream(strev)
	}
}

PnoteEnv {
	*initClass {
		Class.initClassTree(SynthDescLib);
		this.addSynthDefs;
	}

	*addSynthDefs { arg maxSize=10;
		SynthDef(\PnoteEnv_adsr, { arg out=0, gate=1, levelScale=1, levelBias=0, timeScale=1;
			var sig;
			sig = EnvGen.kr(\env.kr(Env.adsr(0.1,0.1,0.8,0.1)), gate, levelScale: levelScale, levelBias: levelBias, timeScale: timeScale, doneAction:2);
			Out.kr(out, sig);
		}).add;

		(2..maxSize).do { arg num;
			SynthDef(( \PnoteEnv_size++num ).asSymbol, { arg out=0, levelScale=1, levelBias=0;
				var sig;
				sig = EnvGen.kr(\env.kr( Env(1!num, 1!(num-1), 0!(num-1)) ), 1, levelScale: levelScale, levelBias: levelBias, doneAction:2);
				ReplaceOut.kr(out, sig);
			}).add;
		};
	}

	*makePayload { arg event, fun;
		if(event[\hasPmodEnvPayload] != true) {
			//debug("attach payload");
			event[\hasPmodEnvPayload] = true;
			event[\finish] = event[\finish].addFunc({ arg ev;
				//"ss".debug("run finish");
				ev.keys.do { arg key;
					if(ev[key].isKindOf(Event)) {
						if([\PmodEnv_payload].includes(ev[key][\type])) {
							//ev[key].payload.debug("run finish");
							ev[key] = ev[key].payload.(ev, key);
						};
					};

				};

			});
		} {
			//Log(\PmodEnv).debug("has already a payload");
		};
		^(type: \PmodEnv_payload, payload: { fun }).yield;
	}

	*env { arg envpat, synthdef, levelScale=1, levelBias=0, timeScale=1;
		^Prout({ arg ev;
			var pat;
			var str, levelstr, biasstr, timestr;
			var envsize;
			envsize = envpat.size;
			synthdef = synthdef ?? { ( \PnoteEnv_size++envsize ).asSymbol };

			pat = Ptuple( 
				envpat.asArray
			).collect{ arg x; [x] };
			str = pat.asStream;
			levelstr = levelScale.asStream;
			biasstr = levelBias.asStream;
			timestr = timeScale.asStream;


			block { arg break;
				var nextenv;
				var prevenv;
				var make_cleanup_fun;
				make_cleanup_fun = { arg lbus;
					{
						//debug("pnoteEnv_generic cleanup_fun");
						if(lbus.index.notNil) {
							var reltime;
							var env = nextenv ?? { prevenv };
							if(env.notNil) {
								reltime = env.first.asEnv.times.last;
							} {
								reltime = 2;
							};
							{
								if(lbus.index.notNil) {
									//[lbus, reltime].debug("free bus");
									lbus.free;
								}
							}.defer(reltime);
						};
					}
				};

				inf.do { arg loopidx;
					var bus;
					var cleanup_fun;
					var envev;
					var nextlevel, nexttime, nextbias;
					var cleanup = EventStreamCleanup.new;
					//loopidx.debug("newloop");
					envev = ();
					prevenv = nextenv;
					nextenv = str.next;
					nextlevel = levelstr.next;
					nextbias = biasstr.next;
					nexttime = timestr.next;
					if(nextenv.isNil or: { nextlevel.isNil } or: { nexttime.isNil } or: { nextbias.isNil }) {
						//debug("break");
						break.value;
					};

					//cleanup.addFunction(ev, { "EventStreamCleanup run".debug; cleanup_fun.() });
					cleanup.addFunction(ev, { cleanup_fun.() });
					ev = this.makePayload(ev, { arg iev, key;
						var env;
						bus = Bus.control(Server.default, 1); 
						env = nextenv ? prevenv;
						//bus.debug("new payload local bus");
						cleanup_fun = make_cleanup_fun.(bus);
						(type: \bus, array: env.asEnv.levels.first, out: bus).play;
						//bus.set(env.asEnv.levels.first);

						envev[\out] = bus;
						[\sustain, \legato, \dur].do { arg key, idx;
							envev[key] = iev[key];
						};
						envev[\instrument] = synthdef;
						envev[\env] = nextenv;
						envev[\levelScale] = nextlevel;
						envev[\levelBias] = nextbias;
						envev[\timeScale] = nexttime;
						//envev[\sendGate] = false; // prevent node not found msg

						envev.play;
						// FIXME: cleanup function accumulate during the whole pattern 
						//	  while bus are already freed after each note
						cleanup.update(iev);
						thisThread.clock.sched(envev[\dur], {
							//bus.debug("clean bus by sched");
							cleanup_fun.()
						});
						//debug("debug7");
						bus.asMap;

					});
				};
			};
			//cleanup.exit(ev);
			//{
			//cleanup_fun.();
			//}.defer(1);
			ev;
		});
	}

	*new { arg levels, times, curves=0, levelScale=1, levelBias=0, timeScale=1;
		var envsize;
		levels = levels ?? { [1,1] };
		times = times ?? { [1] };
		^this.env(Env(levels, times, curves), nil, levelScale, levelBias, timeScale);
	}

	*adsr { arg attack=0.1, decay=0.1, sustainLevel=0.8, release=0.1, peakLevel=1, curves=0, 
				levelScale=1, levelBias=0, timeScale=1;
		^this.env(
			Env.adsr(attack, decay, sustainLevel, release, peakLevel, curves), 
			\PnoteEnv_adsr, levelScale, levelBias, timeScale
		);
	}
}

PpatEnv {
	*new { arg ...args;
		^Ptuple( Env(*args).asArray ).collect{ arg x; [x] }
	}
	*adsr { arg ...args;
		^Ptuple( Env.adsr(*args).asArray ).collect{ arg x; [x] }
	}
}
