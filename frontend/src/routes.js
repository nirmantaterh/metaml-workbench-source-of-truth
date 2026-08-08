
// Common Routes
export const CommonRoutes = {
    Home: { path: "/" },
};

// Workbench Routes
export const WorkbenchRoutes = {
    SamplePage: { path: `/wb/sample` },
    // legacy path, kept working for anyone with it bookmarked/loaded - CreateProject below is
    // the one the Transmute menu actually links to now
    ModelPage: { path: `/wb/model` },
    // New scope item 1: Transmute menu is just these two now. Both land on the same editor -
    // CreateProject always starts blank, EditProject picks a saved model first (see
    // EditProjectListPage) then opens the editor already loaded with it.
    CreateProject: { path: `/wb/model/new` },
    EditProject: { path: `/wb/model/edit` },
    ModelEditor: { path: `/wb/model/:id` },
    // Separate top-level menu per scope item 1 - Connect/Evolve/Bridge move here, out of the
    // model editor's own toolbar
    EvolvePage: { path: `/wb/evolve` },
};