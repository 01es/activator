define(["./view", "core/pluginapi"], function(Viewer, api){

	return api.Plugin({
		id: 'code',
		name: "Code",
		icon: "",
		url: "#code" /*,
		routes: {
			'code': function(bcs) {
				return $.map(bcs, function(crumb, idx) {
					var file = bcs.slice(1, 1+idx).join('/');
					return {
						widget: Viewer,
						file: file
					};
				});
			}
		}*/
	});

});
