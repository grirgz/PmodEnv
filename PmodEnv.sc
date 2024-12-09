
PmodEnv : Pattern {
	var valPat, timePat, curvePat, repeats;

	*new { arg valPat, timePat, curvePat, repeats=1;
		^super.newCopyArgs(valPat, timePat, curvePat, repeats);
	}

    //initClass

	storeArgs { ^[valPat, timePat, curvePat, repeats] }

	embedInStream { arg ev;
		var bus = Bus.control(Server.default, 1);
		var timestr;
		var running = true;
		var cleanup = EventStreamCleanup.new;
		var patplayer;
		var finish_fun;
		var cleanup_fun = {
			patplayer.stop;
			running = false;
			//"pmodenv: CLEANUP".debug;
			{
				2.wait;
				if(bus.index.notNil) {
					bus.free;
				}
			}.fork;
		};

		//timePat = timePat ??  { Plazy({ ev[\dur] }).loop };
		timePat = timePat ??  1;
		curvePat = curvePat ??  { 0 };

		cleanup.addFunction(ev, cleanup_fun);

		CmdPeriod.doOnce({ 
			if(bus.index.notNil) {
				bus.free 
			}
		});

		finish_fun = {
			patplayer = Pmono(\modenvmono,
				\out, bus,
				\itrig, 1,
				[ \dur, \env ], Prout({ arg monoev;
					var valstr = valPat.asStream;
					var curvestr = curvePat.asStream;
					var previous = valstr.next;
					var time;
					timestr = timePat.asStream;

					block { arg break;
						valstr.do { arg val;
							var prev = previous;
							var curve;
							//val.debug("pmodenv val");
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
							monoev = [time, [ Env([prev,val],[time]/thisThread.clock.tempo, curve) ]].yield;

							previous = val;
						};
					};
					running = false;
					monoev;
				}),
				\legato, 1,
			).play;
		};

		if(ev[\finish].isKindOf(Function)) {
			var oldfun = ev[\finish];
			ev[\finish] = oldfun.addFunc(finish_fun);
		} {
			ev[\finish] = finish_fun;
		};

		while{ running == true } {
			cleanup.update(ev, cleanup_fun);
			ev = bus.asMap.yield;
		};
		cleanup.exit(ev);
		^ev;
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
