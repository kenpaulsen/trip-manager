The content here mirrors the content in the JSFTemplating project on java.net.

NOTE: When using full-page state saving, initPage/initPost won't get fired on
	  POST (view isn't recreated the same way).  At this time, this is not
	  supported.

2.2.3
  - Added Mojarra hack utility to replace UIInstruction w/ HTMLOutputText
  - Added Mojarra hack to avoid adding PreRenderView for deleted components
    during the RenderingPhase (it recreates them for some reason!)
  - Changed PostAddToView dispatching in jsft:event to only fire once per
    component, per request
2.2.2
  - Change ModComponentBase to implement NamingContainer
2.2.1
  - Added Util.unHtmlEscape(String)
  - Fixed PMD / FindBugs / minor errors
  - Fixed setAttributeComponent for dynamic attributes
2.1.12
  - Added support for "src" attribute on add/insert/replace components.
  - Added before="true|false" for add component.
2.1.11-b2
  - Added support for manipulating UIComponent tree (see: ComponentCommands)
  - Added <jsft:insertComponent target="..." before="true|false">
  - Added <jsft:addComponent target="...">
  - Added <jsft:removeComponent target="...">
  - Added <jsft:replaceComponent target="...">
2.1.10-b2:
  - Enhanced htmlEscape to include escaping of quotes
  - Fixed handling of comments in readUtil() utility, was resulting in an
    infinite loop when comments were inside events.
2.1.9-b3:
  - Fixed bug which causes eventListeners to be dual registered
2.1.8:
  - Added support for initPage, initGet, initPost

