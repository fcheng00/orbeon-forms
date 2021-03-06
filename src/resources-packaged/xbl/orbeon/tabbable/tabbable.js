/**
 * Copyright (C) 2013 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
(function() {

    var $ = ORBEON.jQuery;
    YAHOO.namespace("xbl.fr");
    YAHOO.xbl.fr.Tabbable = function() {};
    ORBEON.xforms.XBL.declareClass(YAHOO.xbl.fr.Tabbable, "xbl-fr-tabbable");
    YAHOO.xbl.fr.Tabbable.prototype = {

        init: function() {
            $(this.container).find('.nav-tabs a').click(function (e) {
                e.preventDefault();
                $(this).tab('show');
            })
        }
    };

})();


