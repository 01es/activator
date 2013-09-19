define(['core/model', 'text!./run.html', 'core/pluginapi', 'core/widgets/log', 'css!./run.css'], function(model, template, api, log, css){

	var ko = api.ko;
	var sbt = api.sbt;

	var runConsole = api.PluginWidget({
		id: 'play-run-widget',
		template: template,
		init: function(parameters){
			var self = this

			this.title = ko.observable("Run");
			this.activeTask = ko.observable(""); // empty string or taskId
			this.mainClasses = ko.observableArray();
			// note: we generally want to use the explicitly-set defaultMainClass even if it
			// isn't in the list of discovered mainClasses
			this.defaultMainClass = ko.observable("");
			this.currentMainClass = ko.observable("");
			this.haveMainClass = ko.computed(function() {
				// when we set mainClasses to empty list (as it is by default), knockout will set
				// currentMainClass to 'undefined'
				return typeof(self.currentMainClass()) == 'string' && self.currentMainClass() != "";
			}, this);

			this.haveActiveTask = model.snap.app.haveActiveTask;
			self.activeTask.subscribe(function() {
				self.haveActiveTask(self.activeTask() != "");
				console.log("activeTask changed", self.haveActiveTask());
			});

			this.startStopLabel = ko.computed(function() {
				if (self.haveActiveTask())
					return "Stop";
				else
					return "Start";
			}, this);
			this.rerunOnBuild = ko.observable(true);
			this.runInConsole = model.snap.app.runInConsole;
			this.restartPending = ko.observable(false);

			api.events.subscribe(function(event) {
				return event.type == 'CompileSucceeded';
			},
			function(event) {
				self.onCompileSucceeded(event);
			});

			this.logModel = new log.Log();
			this.logScroll = this.logModel.findScrollState();
			this.outputModel = new log.Log();
			this.outputScroll = this.outputModel.findScrollState();
			this.playAppLink = ko.observable('');
			this.playAppStarted = ko.computed(function() { return self.haveActiveTask() && this.playAppLink() != ''; }, this);
			this.atmosLink = model.snap.app.atmosLink;
			this.atmosCompatible = model.snap.app.hasConsole;
			this.status = ko.observable('Application is stopped.');

			model.snap.app.runInConsole.subscribe(function() {
				console.log("runInConsole changed", model.snap.app.runInConsole())
				self.doRestart();
			});
		},
		update: function(parameters){
		},
		loadMainClasses: function(success, failure) {
			var self = this;
			sbt.runTask({
				task: 'discovered-main-classes',
				onmessage: function(event) {
					console.log("event discovering main classes", event);
				},
				success: function(data) {
					console.log("discovered main classes result", data);
					var names = [];
					if (data.type == 'GenericResponse') {
						names = data.params.names;
					}
					sbt.runTask({
						task: 'main-class',
						onmessage: function(event) {
							console.log("event getting default main class", event);
						},
						success: function(data) {
							console.log("default main class result", data);
							var name = '';
							// 'name' won't be in here if mainClass was unset
							if (data.type == 'GenericResponse' && 'name' in data.params) {
								name = data.params.name;
							}
							success({ name: name, names: names });
						},
						failure: function(status, message) {
							console.log("getting default main class failed", message);
							// we don't treat this as failure, just as no default set
							success({ name: '', names: names });
						}
					});
				},
				failure: function(status, message) {
					console.log("getting main classes failed", message);
					failure(status, message);
				}
			});
		},
		onCompileSucceeded: function(event) {
			var self = this;

			console.log("Compile succeeded, reloading main class information");

			// whether we get main classes or not we'll try to
			// run, but get the main classes first so we don't
			// fail if there are multiple main classes.
			function afterLoadMainClasses() {
				if (self.rerunOnBuild() && !self.haveActiveTask()) {
					self.doRun(true); // true=triggeredByBuild
				}
			}

			// update our list of main classes
			this.loadMainClasses(function(data) {
				// SUCCESS
				console.log("GOT main class info ", data);

				// hack because run-main doesn't work on Play right now.
				if (model.snap.app.hasPlay()) {
					console.log("OVERRIDING main class info due to Play app; dropping it all");
					data.name = '';
					data.names = [];
				}

				self.defaultMainClass(data.name);
				console.log("Set default main class to " + self.defaultMainClass());
				// ensure the default configured class is in the menu
				if (self.defaultMainClass() != '' && data.names.indexOf(self.defaultMainClass()) < 0)
					data.names.push(self.defaultMainClass());

				// when we set mainClasses, knockout will immediately also set currentMainClass to one of these
				// due to the data binding on the option menu.
				var actualCurrent = self.currentMainClass();
				if (typeof(actualCurrent) == 'undefined')
					actualCurrent = '';
				var newCurrent = '';

				console.log("Current main class was: '" + actualCurrent + "'");
				// so here's where knockout makes currentMainClass into something crazy
				self.mainClasses(data.names);
				console.log("Set main class options to " + self.mainClasses());

				// only force current selection to change if it's no longer
				// discovered AND no longer explicitly configured in the build.
				if (actualCurrent != '' && self.mainClasses().indexOf(actualCurrent) >= 0) {
					newCurrent = actualCurrent;
					console.log("Keeping current main class since it still exists: '" + newCurrent + "'");
				}

				// if no existing setting, try to set it
				if (newCurrent == '') {
					if (self.defaultMainClass() != '') {
						console.log("Setting current main class to the default " + self.defaultMainClass());
						newCurrent = self.defaultMainClass();
					} else if (self.mainClasses().length > 0) {
						console.log("Setting current main class to the first in our list");
						newCurrent = self.mainClasses()[0];
					} else {
						console.log("We have nothing to set the current main class to");
						newCurrent = '';
					}
				}

				console.log("Current main class is now: '" + newCurrent + "'");
				self.currentMainClass(newCurrent);

				afterLoadMainClasses();
			},
			function(status, message) {
				// FAIL
				console.log("FAILED to set up main classes");
				afterLoadMainClasses();
			});
		},
		doAfterRun: function() {
			var self = this;
			self.activeTask("");
			self.playAppLink("");
			self.atmosLink("");
			if (self.restartPending()) {
				self.doRun(false); // false=!triggeredByBuild
			}
		},
		doRun: function(triggeredByBuild) {
			var self = this;

			self.logModel.clear();
			self.outputModel.clear();

			if (triggeredByBuild) {
				self.logModel.info("Build succeeded, running...");
				self.status('Build succeeded, running...');
			} else if (self.restartPending()) {
				self.status('Restarting...');
				self.logModel.info("Restarting...");
			} else {
				self.status('Running...');
				self.logModel.info("Running...");
			}

			self.restartPending(false);

			var task = {};
			if (self.haveMainClass()) {
				task.task = 'run-main';
				task.params = { mainClass: self.currentMainClass() };
			} else {
				task.task = 'run';
			}

			if (model.snap.app.runInConsole()) {
				task.task = 'atmos:' + task.task;
			}

			var taskId = sbt.runTask({
				task: task,
				onmessage: function(event) {
					if (event.type == 'LogEvent') {
						var logType = event.entry.type;
						if (logType == 'stdout' || logType == 'stderr') {
							self.outputModel.event(event);
						} else {
							self.logModel.event(event);
						}
					} else if (event.type == 'Started') {
						// our request went to a fresh sbt, and we witnessed its startup.
						// we may not get this event if an sbt was recycled.
						// we move "output" to "logs" because the output is probably
						// just sbt startup messages that were not redirected.
						self.logModel.moveFrom(self.outputModel);
					} else if (event.id == 'playServerStarted') {
						var port = event.params.port;
						var url = 'http://localhost:' + port;
						self.playAppLink(url);
					} else if (event.id == 'atmosStarted') {
						self.atmosLink(event.params.uri);
					} else {
						self.logModel.leftoverEvent(event);
					}
				},
				success: function(data) {
					console.log("run result: ", data);
					if (data.type == 'GenericResponse') {
						self.logModel.info('Run complete.');
						self.status('Run complete');
					} else {
						self.logModel.error('Unexpected reply: ' + JSON.stringify(data));
					}
					self.doAfterRun();
				},
				failure: function(status, message) {
					console.log("run failed: ", status, message)
					self.status('Run failed');
					self.logModel.error("Failed: " + status + ": " + message);
					self.doAfterRun();
				}
			});
			self.activeTask(taskId);
		},
		doStop: function() {
			var self = this;
			if (self.haveActiveTask()) {
				sbt.killTask({
					taskId: self.activeTask(),
					success: function(data) {
						console.log("kill success: ", data)
					},
					failure: function(status, message) {
						console.log("kill failed: ", status, message)
						self.status('Unable to stop');
						self.logModel.error("HTTP request to kill task failed: " + message)
					}
				});
			}
		},
		startStopButtonClicked: function(self) {
			console.log("Start or Stop was clicked");
			if (self.haveActiveTask()) {
				// stop
				self.restartPending(false);
				self.doStop();
			} else {
				// start
				self.doRun(false); // false=!triggeredByBuild
			}
		},
		doRestart: function() {
			this.doStop();
			this.restartPending(true);
		},
		restartButtonClicked: function(self) {
			console.log("Restart was clicked");
			self.doRestart();
		},
		onPreDeactivate: function() {
			this.logScroll = this.logModel.findScrollState();
			this.outputScroll = this.outputModel.findScrollState();
		},
		onPostActivate: function() {
			this.logModel.applyScrollState(this.logScroll);
			this.outputModel.applyScrollState(this.outputScroll);
		}
	});

	return api.Plugin({
		id: 'run',
		name: "Run",
		icon: "▶",
		url: "#run",
		routes: {
			'run': function() { api.setActiveWidget(runConsole); }
		},
		widgets: [runConsole]
	});
});
